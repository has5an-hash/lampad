using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using LemPad.Companion;

Console.OutputEncoding = System.Text.Encoding.UTF8;
Console.Title = "LemPad Companion";

Console.WriteLine("======================================");
Console.WriteLine("           LemPad Companion");
Console.WriteLine("======================================");
Console.WriteLine();
Console.WriteLine("گوشی و کامپیوتر باید روی یک شبکه Wi‑Fi باشند.");
Console.WriteLine("در اپ لم‌پد یکی از IPهای زیر را وارد کنید:");
Console.WriteLine();

foreach (var ip in GetLocalIpv4())
{
    Console.WriteLine($"  {ip}");
}

Console.WriteLine();
Console.WriteLine($"UDP Port: {LemPadServer.Port}");
Console.WriteLine("اگر Windows Firewall سؤال کرد، دسترسی Private network را Allow کنید.");
Console.WriteLine("برای خروج Ctrl+C را بزنید.");
Console.WriteLine();

using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    cts.Cancel();
};

await using var server = new LemPadServer();
var focusMonitor = new FocusMonitor();

focusMonitor.EditableFocusChanged += editable =>
{
    _ = Task.Run(async () =>
    {
        if (!server.HasClient) return;
        await server.SendAsync(editable ? "DICTATION_ON" : "DICTATION_OFF");
        Console.WriteLine(editable ? "🎙 Voice typing ON" : "🎙 Voice typing OFF");
    });
};

var serverTask = server.RunAsync(cts.Token);
var focusTask = focusMonitor.RunAsync(cts.Token);

await Task.WhenAll(serverTask, focusTask);

static IEnumerable<IPAddress> GetLocalIpv4()
{
    return NetworkInterface.GetAllNetworkInterfaces()
        .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                    n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
        .SelectMany(n => n.GetIPProperties().UnicastAddresses)
        .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork &&
                    !IPAddress.IsLoopback(a.Address))
        .Select(a => a.Address)
        .Distinct();
}
