using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace WoZai.Next.Mesh;

/// <summary>Owns the native child even if the UI process crashes or exits mid-cleanup.</summary>
internal static class CoreJob
{
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern SafeFileHandle CreateJobObjectW(nint attributes, string? name);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool SetInformationJobObject(SafeFileHandle job, int type, byte[] info, uint length);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool AssignProcessToJobObject(SafeFileHandle job, nint process);
    public static SafeFileHandle Attach(Process process)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException();
        if (!Environment.Is64BitProcess) throw new IOException("requires_x64");
        var job=CreateJobObjectW(0,null);
        try {
            // JOBOBJECT_EXTENDED_LIMIT_INFORMATION on Win64: limit flags at offset16.
            var limits=new byte[144]; BitConverter.GetBytes(0x2000u).CopyTo(limits,16);
            if (job.IsInvalid || !SetInformationJobObject(job,9,limits,(uint)limits.Length) || !AssignProcessToJobObject(job,process.Handle))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            return job;
        } catch { job.Dispose(); throw; }
    }
}
