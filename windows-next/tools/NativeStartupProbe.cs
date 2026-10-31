using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

// Used only by the isolated Windows acceptance fixture after a native startup failure.
public static class NativeStartupProbe
{
    [StructLayout(LayoutKind.Explicit, Size = 176)]
    private struct DebugEvent
    {
        [FieldOffset(0)] public uint Code;
        [FieldOffset(4)] public uint ProcessId;
        [FieldOffset(8)] public uint ThreadId;
        [FieldOffset(16)] public uint ExceptionCode;
        [FieldOffset(40)] public uint NumberParameters;
        [FieldOffset(48)] public ulong Parameter0;
        [FieldOffset(56)] public ulong Parameter1;
        [FieldOffset(64)] public ulong Parameter2;
        [FieldOffset(168)] public uint FirstChance;
        [FieldOffset(16)] public IntPtr StringData;
        [FieldOffset(24)] public ushort Unicode;
        [FieldOffset(26)] public ushort StringLength;
    }
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool DebugActiveProcess(int processId);
    [DllImport("kernel32.dll")] private static extern bool DebugSetProcessKillOnExit(bool kill);
    [DllImport("kernel32.dll")] private static extern bool WaitForDebugEventEx(out DebugEvent value, uint milliseconds);
    [DllImport("kernel32.dll")] private static extern bool ContinueDebugEvent(uint processId, uint threadId, uint status);
    [DllImport("kernel32.dll")] private static extern bool DebugActiveProcessStop(int processId);
    [DllImport("kernel32.dll")] private static extern IntPtr OpenProcess(uint access, bool inherit, int id);
    [DllImport("kernel32.dll")] private static extern bool ReadProcessMemory(IntPtr process, IntPtr address, byte[] buffer, UIntPtr size, out UIntPtr read);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);

    private static byte[] Read(IntPtr process, ulong address, int length)
    {
        var bytes = new byte[Math.Min(length, 65536)];
        if (address == 0 || !ReadProcessMemory(process, (IntPtr)address, bytes, (UIntPtr)bytes.Length, out var read)) return new byte[0];
        Array.Resize(ref bytes, (int)read); return bytes;
    }
    private static string WideText(IntPtr process, ulong address, int characters = 2048)
        => Encoding.Unicode.GetString(Read(process,address,characters*2)).Split('\0')[0];

    public static void Run(string executable, string workingDirectory, string output)
    {
        using (var log = new StreamWriter(output, false, Encoding.UTF8) { AutoFlush = true })
        using (var process = Process.Start(new ProcessStartInfo(executable) { WorkingDirectory = workingDirectory, UseShellExecute = false }))
        {
            if (process == null) throw new IOException("Cannot start native probe");
            if (!DebugActiveProcess(process.Id)) { log.WriteLine("Attach error: " + Marshal.GetLastWin32Error()); return; }
            DebugSetProcessKillOnExit(false);
            var handle = OpenProcess(0x0010 | 0x0400, false, process.Id);
            var deadline = DateTime.UtcNow.AddSeconds(30);
            try
            {
                while (DateTime.UtcNow < deadline)
                {
                    if (!WaitForDebugEventEx(out var ev, 500)) { if(process.HasExited)break; continue; }
                    uint disposition = 0x00010002;
                    if (ev.Code == 1)
                    {
                        log.WriteLine("Exception 0x" + ev.ExceptionCode.ToString("X8") + " firstChance=" + ev.FirstChance + " parameters=" + ev.NumberParameters);
                        if (ev.ExceptionCode == 0x40080201 && ev.NumberParameters >= 3)
                            log.WriteLine("WinRT origin HRESULT=0x" + ev.Parameter0.ToString("X") + " " + WideText(handle, ev.Parameter2, (int)Math.Min(ev.Parameter1, 4096)));
                        if (ev.ExceptionCode == 0xc000027b && ev.NumberParameters >= 2)
                        {
                            for (int i = 0; i < Math.Min((long)ev.Parameter1, 16); i++)
                            {
                                var pointer = Read(handle,ev.Parameter0+(ulong)i*8,8);
                                if(pointer.Length!=8)continue;
                                var info=Read(handle,BitConverter.ToUInt64(pointer,0),56);
                                if(info.Length<24)continue;
                                uint form=BitConverter.ToUInt32(info,12)&3;
                                log.WriteLine("Stowed HRESULT=0x"+BitConverter.ToUInt32(info,8).ToString("X8")+" form="+form);
                                if(form==2)log.WriteLine(WideText(handle,BitConverter.ToUInt64(info,16)));
                            }
                        }
                        // Only consume the debugger's attach breakpoint; preserve all app exception handling.
                        if(ev.ExceptionCode!=0x80000003)disposition=0x80010001;
                    }
                    else if(ev.Code==8)
                    {
                        var data=Read(handle,(ulong)ev.StringData.ToInt64(),Math.Min(ev.StringLength, (ushort)16000)*(ev.Unicode!=0?2:1));
                        log.WriteLine((ev.Unicode!=0?Encoding.Unicode:Encoding.UTF8).GetString(data).Split('\0')[0]);
                    }
                    ContinueDebugEvent(ev.ProcessId,ev.ThreadId,disposition);
                    if(ev.Code==5)break;
                }
            }
            finally
            {
                DebugActiveProcessStop(process.Id); CloseHandle(handle);
                if(!process.HasExited)process.Kill(true);
            }
        }
    }
}
