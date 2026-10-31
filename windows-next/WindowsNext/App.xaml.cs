using Microsoft.UI.Xaml;

namespace WoZai.Next;

public partial class App : Application
{
    private MainWindow? _window;
    public App()
    {
        InitializeComponent();
        UnhandledException += (_, e) =>
        {
            Console.Error.WriteLine("WinUI unhandled failure: " + e.Exception);
            if (_window is not null)
            {
                e.Handled = true;
                _window.ReportError(e.Exception.Message);
            }
        };
    }
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try { _window = new MainWindow(); _window.Activate(); }
        catch(Exception error) { Console.Error.WriteLine("WinUI startup failure: " + error); throw; }
    }
}
