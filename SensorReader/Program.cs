using System;
using System.Runtime.InteropServices;
using System.Threading;
using LibreHardwareMonitor.Hardware;

// Persistent CPU temperature reader.
// Outputs "cpuTemp=XX.X" once per second to stdout.
// Accepts "fan_on" / "fan_off" from stdin but cannot act on them --
// the GE76 EC blocks all software fan control paths (WinRing0, WMI, SendInput).
// Exits when stdin closes. Must be run as administrator.

internal static class Program
{
    private static string _logPath = "";
    private static void Log(string msg)
    {
        try { System.IO.File.AppendAllText(_logPath, DateTime.Now.ToString("HH:mm:ss") + " " + msg + "\n"); }
        catch { }
        Console.Error.WriteLine(msg);
    }

    private static void Main(string[] args)
    {
        _logPath = System.IO.Path.Combine(
            System.IO.Path.GetDirectoryName(Environment.ProcessPath) ?? ".", "tempsensor_debug.log");

        Log("TempSensor started.");

        var computer = new Computer { IsCpuEnabled = true };
        try { computer.Open(); }
        catch (Exception e) { Log("hardware open failed: " + e.Message); Environment.Exit(1); }

        // Stdin command listener (fan commands accepted but no-op -- EC blocks software control)
        new Thread(() =>
        {
            try
            {
                string? line;
                while ((line = Console.In.ReadLine()) != null)
                {
                    line = line.Trim('﻿', ' ');
                    if (line == "fan_on" || line == "fan_off")
                        Log("cmd=" + line + " (no-op: EC blocks software fan control on GE76)");
                }
            }
            catch { }
            computer.Close();
            Environment.Exit(0);
        }) { IsBackground = true }.Start();

        // CPU temp loop
        while (true)
        {
            double cpuTemp = -1;
            foreach (var hw in computer.Hardware)
            {
                hw.Update();
                foreach (var sensor in hw.Sensors)
                {
                    if (sensor.SensorType == SensorType.Temperature && sensor.Name == "Core Average")
                    { cpuTemp = sensor.Value ?? -1; break; }
                }
                if (cpuTemp >= 0) break;
            }
            Console.WriteLine("cpuTemp=" + cpuTemp.ToString("F1"));
            Console.Out.Flush();
            Thread.Sleep(1000);
        }
    }
}
