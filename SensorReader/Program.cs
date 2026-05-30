using System;
using System.Reflection;
using System.Threading;
using LibreHardwareMonitor.Hardware;

// Persistent CPU temperature reader + Cooler Boost controller.
// Outputs "cpuTemp=XX.X" once per second to stdout.
// Reads "fan_on" / "fan_off" commands from stdin and writes to EC register 0x98.
// Exits when stdin closes. Must be run as administrator.

// EC I/O ports
const uint EC_DATA = 0x62;
const uint EC_SC   = 0x66;
const byte EC_IBF  = 0x02; // input buffer full bit

var computer = new Computer { IsCpuEnabled = true };

try { computer.Open(); }
catch (Exception e)
{
    Console.Error.WriteLine("TempSensor: failed to open hardware: " + e.Message);
    Environment.Exit(1);
}

// Grab Ring0 IO methods from LHM via reflection (internal class, loaded by computer.Open)
var ring0 = typeof(Computer).Assembly.GetType("LibreHardwareMonitor.Interop.Ring0");
var readPort  = ring0?.GetMethod("ReadIoPort",  BindingFlags.NonPublic | BindingFlags.Static,
                    null, new[] { typeof(uint) }, null);
var writePort = ring0?.GetMethod("WriteIoPort", BindingFlags.NonPublic | BindingFlags.Static,
                    null, new[] { typeof(uint), typeof(byte) }, null);

if (readPort == null || writePort == null)
    Console.Error.WriteLine("TempSensor: Ring0 IO methods not found -- fan control disabled");

bool WaitEC()
{
    for (int i = 0; i < 100; i++)
    {
        byte s = (byte)readPort!.Invoke(null, new object[] { EC_SC })!;
        if ((s & EC_IBF) == 0) return true;
        Thread.Sleep(1);
    }
    return false;
}

void WriteEC(byte reg, byte value)
{
    if (writePort == null || readPort == null) return;
    try
    {
        if (!WaitEC()) return;
        writePort.Invoke(null, new object[] { EC_SC,   (byte)0x81 }); // WRITE cmd
        if (!WaitEC()) return;
        writePort.Invoke(null, new object[] { EC_DATA, reg          }); // register
        if (!WaitEC()) return;
        writePort.Invoke(null, new object[] { EC_DATA, value        }); // value
    }
    catch (Exception e) { Console.Error.WriteLine("TempSensor: EC write error: " + e.Message); }
}

// Stdin listener: "fan_on" -> EC 0x98=0x80, "fan_off" -> EC 0x98=0x00, EOF -> exit
new Thread(() =>
{
    try
    {
        string? line;
        while ((line = Console.In.ReadLine()) != null)
        {
            if      (line == "fan_on")  WriteEC(0x98, 0x80);
            else if (line == "fan_off") WriteEC(0x98, 0x00);
        }
    }
    catch { }
    computer.Close();
    Environment.Exit(0);
}) { IsBackground = true }.Start();

while (true)
{
    double cpuTemp = -1;
    foreach (var hw in computer.Hardware)
    {
        hw.Update();
        foreach (var sensor in hw.Sensors)
        {
            if (sensor.SensorType == SensorType.Temperature && sensor.Name == "Core Average")
            {
                cpuTemp = sensor.Value ?? -1;
                break;
            }
        }
        if (cpuTemp >= 0) break;
    }
    Console.WriteLine("cpuTemp=" + cpuTemp.ToString("F1"));
    Console.Out.Flush();
    Thread.Sleep(1000);
}
