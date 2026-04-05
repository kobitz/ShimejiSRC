using System;
using LibreHardwareMonitor.Hardware;

// Reads system sensor data via LibreHardwareMonitorLib.dll
// Outputs one line per sensor: key=value
// Must be run as administrator.
// LibreHardwareMonitorLib.dll must be in the same folder.

var computer = new Computer
{
    IsCpuEnabled     = true,
    IsGpuEnabled     = true,
    IsMemoryEnabled  = true,
    IsBatteryEnabled = true,
};

try
{
    computer.Open();
}
catch (System.IO.FileNotFoundException)
{
    // RAMSPDToolkit not available - disable memory and retry
    computer.IsMemoryEnabled = false;
    computer.Open();
}

foreach (var hw in computer.Hardware)
{
    hw.Update();
    foreach (var sub in hw.SubHardware)
        sub.Update();
}

double cpuTemp      = -1;
double cpuLoad      = -1;
double gpuTemp      = -1;
double gpuLoad      = -1;
double ramLoad      = -1;
double batteryLevel = -1;

foreach (var hw in computer.Hardware)
{
    foreach (var sensor in hw.Sensors)
    {
        var val  = sensor.Value ?? -1f;
        var type = sensor.SensorType;
        var name = sensor.Name;

        if (type == SensorType.Temperature && name == "Core Average")  cpuTemp      = val;
        if (type == SensorType.Load        && name == "CPU Total")     cpuLoad      = val;
        if (type == SensorType.Temperature && name == "GPU Core")      gpuTemp      = val;
        if (type == SensorType.Load        && name == "GPU Core")      gpuLoad      = val;
        if (type == SensorType.Load        && name == "Memory")        ramLoad      = val;
        if (type == SensorType.Level       && name == "Charge Level")  batteryLevel = val;
    }
}

computer.Close();

Console.WriteLine($"cpuTemp={cpuTemp:F1}");
Console.WriteLine($"cpuLoad={cpuLoad:F1}");
Console.WriteLine($"gpuTemp={gpuTemp:F1}");
Console.WriteLine($"gpuLoad={gpuLoad:F1}");
Console.WriteLine($"ramLoad={ramLoad:F1}");
Console.WriteLine($"batteryLevel={batteryLevel:F1}");
