using System;
using System.Diagnostics;
using System.Management;
using System.Text;
using System.Threading;
using LibreHardwareMonitor.Hardware;

// Persistent CPU temperature reader + MSI Cooler Boost toggle.
//
// Default (no args): outputs "cpuTemp=XX.X" once per second to stdout, and
// listens on stdin for "fan_on" / "fan_off" to toggle Cooler Boost. Exits when
// stdin closes. Spawned by the Java app (already elevated for LHM Ring0).
//
// CLI modes (run from an elevated prompt to test directly):
//   TempSensor.exe boost on|off    -> toggle MSI Cooler Boost (read-modify-write + readback)
//   TempSensor.exe read <hexReg>   -> read one EC register via Get_Data
//   TempSensor.exe write <hexReg> <hexVal>  -> write one EC register via Set_Data
//   TempSensor.exe dump            -> dump MSI_ACPI EC views (diagnostic)
//
// MSI_ACPI WMI (root\wmi): addressed EC access is Get_Data / Set_Data (in+out),
// package layout Bytes[0]=address, Bytes[1]=value. (Get_EC/Set_EC operate on a
// fixed BIOS-info block, NOT addressable EC.) Cooler Boost = register 0x98 bit
// 0x80; the sibling bit 0x02 must be preserved, so writes are read-modify-write.
// Requires administrator.

internal static class Program
{
    private const byte COOLER_BOOST_REG = 0x98;
    private const byte COOLER_BOOST_BIT = 0x80;

    private static bool loggedTempSensor = false;

    private static readonly object _logLock = new object();
    private static string? _logPath;

    private static void Log(string msg)
    {
        string line = DateTime.Now.ToString("HH:mm:ss") + "  " + msg;
        Console.Error.WriteLine(line);
        try
        {
            _logPath ??= System.IO.Path.Combine(
                System.IO.Path.GetDirectoryName(Environment.ProcessPath ?? "") ?? ".",
                "tempsensor.log");
            lock (_logLock)
                System.IO.File.AppendAllText(_logPath, line + Environment.NewLine);
        }
        catch { }
    }

    // Pick the most representative CPU temperature across vendors. Intel exposes
    // "Core Average"; Ryzen exposes "Core (Tctl/Tdie)". Falls back to any CPU-node
    // temperature sensor so AMD/other chips still report. ADDITIVE: "Core Average"
    // still wins when present, so Intel behavior is unchanged. Logs the chosen
    // sensor name once (diagnostic — confirms the fallback on unfamiliar hardware).
    private static double ReadCpuTemp(Computer computer)
    {
        double best = -1;
        int bestRank = int.MaxValue;
        string bestName = "";
        foreach (var hw in computer.Hardware)
        {
            hw.Update();
            foreach (var s in hw.Sensors)
            {
                if (s.SensorType != SensorType.Temperature) continue;
                double v = s.Value ?? -1;
                if (v < 0) continue;
                int rank = TempRank(s.Name ?? "");
                if (rank < bestRank) { bestRank = rank; best = v; bestName = s.Name ?? ""; }
            }
        }
        if (!loggedTempSensor && best >= 0)
        {
            loggedTempSensor = true;
            Log("CPU temp sensor: \"" + bestName + "\"");
        }
        return best;
    }

    // Lower rank = preferred. Only CPU-node sensors reach here (IsCpuEnabled only).
    private static int TempRank(string name)
    {
        if (name == "Core Average") return 0;                           // Intel package average
        if (name == "Core (Tctl/Tdie)") return 1;                       // Ryzen control temperature
        if (name.Contains("Tctl") || name.Contains("Tdie")) return 2;   // other Ryzen Tctl/Tdie variants
        if (name == "CPU Package" || name == "Package") return 3;       // Intel/other package temp
        if (name.Contains("Core Max")) return 4;
        return 9;                                                       // any other CPU temperature sensor
    }

    private static void Main(string[] args)
    {
        // ---- CLI test/control modes ----
        if (args.Length >= 1)
        {
            string mode = args[0].ToLowerInvariant();
            try
            {
                if (mode == "boost")
                {
                    bool on = args.Length >= 2 &&
                        (args[1].Equals("on", StringComparison.OrdinalIgnoreCase) || args[1] == "1");
                    SetCoolerBoost(on);
                    return;
                }
                if (mode == "read")
                {
                    byte reg = ParseHex(args.Length >= 2 ? args[1] : "98");
                    Console.WriteLine("EC 0x" + reg.ToString("X2") + " = 0x" + ReadReg(reg).ToString("X2"));
                    return;
                }
                if (mode == "write" && args.Length >= 3)
                {
                    byte reg = ParseHex(args[1]);
                    byte val = ParseHex(args[2]);
                    WriteReg(reg, val);
                    Console.WriteLine("EC 0x" + reg.ToString("X2") + " <- 0x" + val.ToString("X2") +
                        ", readback 0x" + ReadReg(reg).ToString("X2"));
                    return;
                }
                if (mode == "dump")
                {
                    DumpEc();
                    return;
                }
                Console.Error.WriteLine("usage: TempSensor.exe [boost on|off | read <hexReg> | write <hexReg> <hexVal> | dump]");
                return;
            }
            catch (Exception e)
            {
                Log("cli error: " + e);
                Environment.Exit(1);
            }
        }

        // ---- Normal sensor + fan-control mode ----
        AppDomain.CurrentDomain.UnhandledException += (s, e) =>
        { try { Log("FATAL unhandled exception: " + e.ExceptionObject); } catch { } };
        Log("===== TempSensor started (pid " + Environment.ProcessId + ") =====");

        var computer = new Computer { IsCpuEnabled = true };
        try { computer.Open(); }
        catch (Exception e) { Log("hardware open failed: " + e.Message); Environment.Exit(1); }

        // Stdin command listener (fan_on / fan_off -> MSI Cooler Boost)
        new Thread(() =>
        {
            try
            {
                string? line;
                while ((line = Console.In.ReadLine()) != null)
                {
                    line = line.Trim('﻿', ' ');
                    if (line != "fan_on" && line != "fan_off") continue;
                    Log("received: " + line);
                    ApplyBoostViaChild(line == "fan_on");
                }
                Log("stdin reached EOF (parent closed the pipe) -- exiting.");
            }
            catch (Exception e) { Log("stdin thread exception -- exiting: " + e.Message); }
            computer.Close();
            Environment.Exit(0);
        }) { IsBackground = true }.Start();

        // CPU temp loop. An intermittent LHM read throwing here used to propagate out
        // of Main and crash the whole process -> Cooler Boost stopped until a respawn.
        // Catch and continue so a transient sensor hiccup can't kill TempSensor.
        int tempErrors = 0;
        while (true)
        {
            try
            {
                double cpuTemp = ReadCpuTemp(computer);
                Console.WriteLine("cpuTemp=" + cpuTemp.ToString("F1"));
                Console.Out.Flush();
            }
            catch (Exception e)
            {
                if (tempErrors++ < 5 || tempErrors % 60 == 0)
                    Log("temp read error #" + tempErrors + " (continuing): " + e.Message);
            }
            Thread.Sleep(1000);
        }
    }

    // Apply a boost toggle in a SHORT-LIVED CHILD PROCESS (our own "boost on/off" CLI
    // mode) instead of doing WMI in this long-running process. A blocking WMI/COM call
    // wedges the whole .NET process -- it stalls the GC, freezing every managed thread,
    // so an in-process watchdog can't recover it (the ~15-min "boost just stops" bug).
    // A separate process CAN be force-killed regardless of its COM state, so a hung EC
    // call kills only the disposable child; this parent never touches WMI, so it can't
    // freeze. stdout is redirected+drained so the child can never write into the cpuTemp
    // pipe back to Shimeji; the child writes its own result line to tempsensor.log.
    private static void ApplyBoostViaChild(bool on)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = Environment.ProcessPath ?? "TempSensor.exe",
                Arguments = on ? "boost on" : "boost off",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            using var child = Process.Start(psi);
            if (child == null) { Log("boost child failed to start"); return; }
            child.OutputDataReceived += (s, e) => { };
            child.ErrorDataReceived += (s, e) => { };
            child.BeginOutputReadLine();
            child.BeginErrorReadLine();
            if (!child.WaitForExit(8000))
            {
                Log("boost child WEDGED (>8s) -- killing it; parent stays healthy.");
                try { child.Kill(true); } catch (Exception ke) { Log("kill failed: " + ke.Message); }
            }
        }
        catch (Exception e) { Log("boost child error: " + e.Message); }
    }

    // ---- MSI Cooler Boost (read-modify-write, preserves the sibling 0x02 bit) ----

    private static void SetCoolerBoost(bool on)
    {
        try
        {
            byte cur = ReadReg(COOLER_BOOST_REG);
            byte next = on ? (byte)(cur | COOLER_BOOST_BIT) : (byte)(cur & ~COOLER_BOOST_BIT);
            WriteReg(COOLER_BOOST_REG, next);
            byte after = ReadReg(COOLER_BOOST_REG);
            Log("cooler boost " + (on ? "ON" : "OFF") + ": 0x98 0x" + cur.ToString("X2") +
                " -> wrote 0x" + next.ToString("X2") + " -> readback 0x" + after.ToString("X2") +
                (after == next ? " OK" : " (WRITE DID NOT TAKE)"));
        }
        catch (Exception e)
        {
            Log("cooler boost " + (on ? "ON" : "OFF") + " failed: " + e.Message);
        }
    }

    // ---- Addressed EC access via MSI_ACPI Get_Data / Set_Data ----
    // Package layout: Bytes[0] = register address, Bytes[1] = value.
    // (Legacy ManagementObject API; modern CIM cmdlets fail on the embedded
    // Package_32 argument with WBEM_E_INVALID_PARAMETER.)

    private static byte ReadReg(byte addr)
    {
        byte[] inb = new byte[32];
        inb[0] = addr;
        byte[]? outb = InvokeData("Get_Data", inb);
        if (outb == null || outb.Length < 2) throw new Exception("Get_Data returned no data");
        return outb[1];
    }

    private static void WriteReg(byte addr, byte val)
    {
        byte[] inb = new byte[32];
        inb[0] = addr;
        inb[1] = val;
        InvokeData("Set_Data", inb);
    }

    private static void DumpEc()
    {
        TryDump("Get_EC      ", "Get_EC", null);
        TryDump("Get_EC2     ", "Get_EC2", null);
        byte[] a98 = new byte[32]; a98[0] = 0x98;
        TryDump("Get_Data[98]", "Get_Data", a98);
    }

    private static void TryDump(string label, string method, byte[]? inBytes)
    {
        try { Console.WriteLine(label + " : " + Hex(InvokeData(method, inBytes))); }
        catch (Exception e) { Console.WriteLine(label + " : ERR " + e.Message); }
    }

    // ---- WMI plumbing ----
    // CRITICAL: every object here is an IDisposable COM wrapper. Leaking them
    // exhausts WMI handles over a long session, after which Set_Data silently
    // fails and Cooler Boost stops working until the process restarts (the bug
    // that "works fresh, dies after a while"). So: connect ONCE and cache the
    // MSI_ACPI instance, dispose every PER-CALL object, and drop the cache on any
    // error so the next call reconnects.

    private static ManagementScope? _wmiScope;
    private static ManagementObject? _msiAcpi;

    private static ManagementObject Acpi()
    {
        if (_msiAcpi != null) return _msiAcpi;
        var scope = new ManagementScope(@"\\.\root\wmi");
        scope.Connect();
        using var searcher = new ManagementObjectSearcher(scope, new ObjectQuery("SELECT * FROM MSI_ACPI"));
        using var results = searcher.Get();
        foreach (ManagementBaseObject mo in results) { _msiAcpi = (ManagementObject)mo; break; }
        if (_msiAcpi == null) throw new Exception("MSI_ACPI instance not found");
        _wmiScope = scope;
        return _msiAcpi;
    }

    private static void InvalidateAcpi()
    {
        try { _msiAcpi?.Dispose(); } catch { }
        _msiAcpi = null;
        _wmiScope = null;
    }

    private static ManagementObject MakePackage(ManagementScope scope, byte[] bytes)
    {
        using var cls = new ManagementClass(scope, new ManagementPath("Package_32"), null);
        ManagementObject pkg = cls.CreateInstance();   // disposed by the caller
        pkg["Bytes"] = bytes;
        return pkg;
    }

    // Invoke an MSI_ACPI method whose Data parameter is in and/or out. Returns the
    // out Package_32 bytes, or null when the method has no out data. inBytes may be
    // null for out-only methods (GetMethodParameters returns null then).
    private static byte[]? InvokeData(string method, byte[]? inBytes)
    {
        try
        {
            var acpi = Acpi();
            var scope = _wmiScope!;
            using ManagementBaseObject? inParams = acpi.GetMethodParameters(method);
            ManagementObject? pkg = null;
            try
            {
                if (inParams != null && inBytes != null)
                {
                    pkg = MakePackage(scope, inBytes);
                    inParams["Data"] = pkg;
                }
                using ManagementBaseObject? outParams = acpi.InvokeMethod(method, inParams, null);
                if (outParams == null) return null;
                using var outPkg = outParams["Data"] as ManagementBaseObject;
                return outPkg?["Bytes"] as byte[];
            }
            finally { pkg?.Dispose(); }
        }
        catch
        {
            InvalidateAcpi();   // force a fresh connection next time
            throw;
        }
    }

    private static byte ParseHex(string s)
    {
        return Convert.ToByte(s.Replace("0x", "").Replace("0X", ""), 16);
    }

    private static string Hex(byte[]? b)
    {
        if (b == null) return "(null)";
        var sb = new StringBuilder();
        for (int i = 0; i < b.Length; i++)
        {
            sb.Append(b[i].ToString("X2"));
            sb.Append((i % 8 == 7) ? "   " : " ");
        }
        return sb.ToString().TrimEnd();
    }
}
