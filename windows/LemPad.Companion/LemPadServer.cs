using System.Net;
using System.Net.Sockets;
using System.Text;

namespace LemPad.Companion;

internal sealed class LemPadServer : IAsyncDisposable
{
    public const int Port = 47891;

    private readonly UdpClient _udp = new(Port);
    private IPEndPoint? _client;

    public bool HasClient => _client is not null;

    public async Task RunAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            UdpReceiveResult received;
            try
            {
                received = await _udp.ReceiveAsync(token);
            }
            catch (OperationCanceledException)
            {
                break;
            }

            _client = received.RemoteEndPoint;

            var message = Encoding.UTF8.GetString(received.Buffer).Trim();
            if (string.IsNullOrWhiteSpace(message)) continue;

            if (message.Equals("HELLO", StringComparison.OrdinalIgnoreCase))
            {
                await SendAsync($"HELLO_ACK|{Environment.MachineName}");
                continue;
            }

            Handle(message);
        }
    }

    public async Task SendAsync(string message)
    {
        if (_client is null) return;
        var bytes = Encoding.UTF8.GetBytes(message);
        await _udp.SendAsync(bytes, _client);
    }

    private static void Handle(string message)
    {
        var parts = message.Split('|');
        if (parts.Length == 0) return;

        try
        {
            switch (parts[0].ToUpperInvariant())
            {
                case "MOVE" when parts.Length >= 3:
                    if (int.TryParse(parts[1], out var dx) && int.TryParse(parts[2], out var dy))
                        InputInjector.Move(dx, dy);
                    break;

                case "SCROLL" when parts.Length >= 2:
                    if (int.TryParse(parts[1], out var delta))
                        InputInjector.Scroll(delta);
                    break;

                case "CLICK" when parts.Length >= 2:
                    if (parts[1].Equals("RIGHT", StringComparison.OrdinalIgnoreCase))
                        InputInjector.ClickRight();
                    else
                        InputInjector.ClickLeft();
                    break;

                case "DOWN" when parts.Length >= 2:
                    InputInjector.MouseDown(parts[1]);
                    break;

                case "UP" when parts.Length >= 2:
                    InputInjector.MouseUp(parts[1]);
                    break;

                case "KEY" when parts.Length >= 2:
                    InputInjector.PressKey(parts[1]);
                    break;

                case "HOTKEY" when parts.Length >= 3:
                    InputInjector.Hotkey(parts.Skip(1).ToArray());
                    break;

                case "TEXT" when parts.Length >= 2:
                    var data = Convert.FromBase64String(parts[1]);
                    InputInjector.TypeUnicode(Encoding.UTF8.GetString(data));
                    break;
            }
        }
        catch
        {
            // Ignore malformed packets instead of terminating the companion.
        }
    }

    public ValueTask DisposeAsync()
    {
        _udp.Dispose();
        return ValueTask.CompletedTask;
    }
}
