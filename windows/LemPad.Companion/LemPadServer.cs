using System.Net;
using System.Net.Sockets;
using System.Text;

namespace LemPad.Companion;

internal sealed class LemPadServer : IAsyncDisposable
{
    public const int Port = 47891;

    private readonly UdpClient _udp = new(Port);
    private readonly NaturalCommandProcessor _commands = new();
    private IPEndPoint? _client;
    private bool _editableFocus;

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
            catch (ObjectDisposedException)
            {
                break;
            }

            _client = received.RemoteEndPoint;

            var message = Encoding.UTF8.GetString(received.Buffer).Trim();
            if (string.IsNullOrWhiteSpace(message)) continue;

            if (message.Equals("HELLO", StringComparison.OrdinalIgnoreCase))
            {
                await SendAsync($"HELLO_ACK|{Environment.MachineName}");
                await SendAsync(_editableFocus ? "FOCUS|EDITABLE" : "FOCUS|COMMAND");
                continue;
            }

            await HandleAsync(message);
        }
    }

    public async Task SendAsync(string message)
    {
        if (_client is null) return;

        try
        {
            var bytes = Encoding.UTF8.GetBytes(message);
            await _udp.SendAsync(bytes, _client);
        }
        catch
        {
            // The phone may temporarily leave Wi-Fi or switch networks.
        }
    }

    public async Task SetEditableFocusAsync(bool editable)
    {
        _editableFocus = editable;
        if (HasClient)
            await SendAsync(editable ? "FOCUS|EDITABLE" : "FOCUS|COMMAND");
    }

    private async Task HandleAsync(string message)
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
                    else if (parts[1].Equals("DOUBLE", StringComparison.OrdinalIgnoreCase))
                        InputInjector.DoubleClickLeft();
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

                case "KEY_DOWN" when parts.Length >= 2:
                    if (InputInjector.HoldKey(parts[1]))
                        await SendAsync($"MODIFIER|{parts[1].ToUpperInvariant()}|ON");
                    break;

                case "KEY_UP" when parts.Length >= 2:
                    if (InputInjector.ReleaseKey(parts[1]))
                        await SendAsync($"MODIFIER|{parts[1].ToUpperInvariant()}|OFF");
                    break;

                case "HOTKEY" when parts.Length >= 3:
                    InputInjector.Hotkey(parts.Skip(1).ToArray());
                    break;

                case "TEXT" when parts.Length >= 2:
                    InputInjector.TypeUnicode(DecodeBase64(parts[1]));
                    break;

                case "VOICE" when parts.Length >= 2:
                {
                    var spoken = DecodeBase64(parts[1]);
                    var result = await _commands.ProcessAsync(spoken, _editableFocus);

                    foreach (var change in result.ModifierChanges)
                    {
                        await SendAsync(
                            $"MODIFIER|{change.Key}|{(change.IsDown ? "ON" : "OFF")}"
                        );
                    }

                    if (!string.IsNullOrWhiteSpace(result.Status))
                    {
                        var encoded = Convert.ToBase64String(
                            Encoding.UTF8.GetBytes(result.Status)
                        );
                        await SendAsync($"STATUS|{encoded}");
                    }

                    break;
                }

                case "RELEASE_ALL":
                    InputInjector.ReleaseAllHeldKeys();
                    await SendAsync("MODIFIER|ALL|OFF");
                    break;
            }
        }
        catch
        {
            // Malformed packets must never terminate the companion.
        }
    }

    private static string DecodeBase64(string payload)
    {
        var data = Convert.FromBase64String(payload);
        return Encoding.UTF8.GetString(data);
    }

    public ValueTask DisposeAsync()
    {
        InputInjector.ReleaseAllHeldKeys();
        _udp.Dispose();
        return ValueTask.CompletedTask;
    }
}
