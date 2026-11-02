using Microsoft.UI.Xaml;

namespace WoZai.Next;

public partial class App : Application
{
    internal static void StartupTrace(string message)
    {
        var path = Environment.GetEnvironmentVariable("WOZAI_NEXT_STARTUP_LOG");
        if (string.IsNullOrEmpty(path)) return;
        try { System.IO.File.AppendAllText(path, DateTime.UtcNow.ToString("O") + " " + message + Environment.NewLine); } catch { }
    }
    private MainWindow? _window;
    public App()
    {
        StartupTrace("App resources initializing");
        InitializeComponent();
        StartupTrace("App resources initialized");
        UnhandledException += (_, e) =>
        {
            Console.Error.WriteLine("WinUI unhandled failure: " + e.Exception);
            StartupTrace("Unhandled: " + e.Exception);
            if (_window is not null)
            {
                e.Handled = true;
                _window.ReportError(e.Exception.Message);
            }
        };
    }
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try { StartupTrace("Creating window"); _window = new MainWindow(); StartupTrace("Activating window"); _window.Activate(); StartupTrace("Window activated"); }
        catch(Exception error) { StartupTrace("Startup failed: " + error); Console.Error.WriteLine("WinUI startup failure: " + error); throw; }
    }
}
