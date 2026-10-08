using System.Runtime.InteropServices;

namespace LemPad.Companion;

internal static class InputInjector
{
    private const int InputMouse = 0;
    private const int InputKeyboard = 1;

    private const uint MouseMove = 0x0001;
    private const uint MouseLeftDown = 0x0002;
    private const uint MouseLeftUp = 0x0004;
    private const uint MouseRightDown = 0x0008;
    private const uint MouseRightUp = 0x0010;
    private const uint MouseWheel = 0x0800;

    private const uint KeyEventKeyUp = 0x0002;
    private const uint KeyEventUnicode = 0x0004;

    private static readonly HashSet<ushort> HeldKeys = new();

    public static void Move(int dx, int dy) => SendMouse(dx, dy, 0, MouseMove);

    public static void Scroll(int delta) => SendMouse(0, 0, delta, MouseWheel);

    public static void ClickLeft()
    {
        SendMouse(0, 0, 0, MouseLeftDown);
        SendMouse(0, 0, 0, MouseLeftUp);
    }

    public static void DoubleClickLeft()
    {
        ClickLeft();
        Thread.Sleep(55);
        ClickLeft();
    }

    public static void ClickRight()
    {
        SendMouse(0, 0, 0, MouseRightDown);
        SendMouse(0, 0, 0, MouseRightUp);
    }

    public static void MouseDown(string button) =>
        SendMouse(0, 0, 0,
            button.Equals("RIGHT", StringComparison.OrdinalIgnoreCase)
                ? MouseRightDown
                : MouseLeftDown);

    public static void MouseUp(string button) =>
        SendMouse(0, 0, 0,
            button.Equals("RIGHT", StringComparison.OrdinalIgnoreCase)
                ? MouseRightUp
                : MouseLeftUp);

    public static void PressKey(string keyName)
    {
        var vk = ResolveKey(keyName);
        if (vk == 0) return;

        KeyDownInternal(vk);
        KeyUpInternal(vk);
    }

    public static bool HoldKey(string keyName)
    {
        var vk = ResolveKey(keyName);
        if (vk == 0) return false;

        lock (HeldKeys)
        {
            if (HeldKeys.Add(vk))
                KeyDownInternal(vk);
        }
        return true;
    }

    public static bool ReleaseKey(string keyName)
    {
        var vk = ResolveKey(keyName);
        if (vk == 0) return false;

        lock (HeldKeys)
        {
            if (HeldKeys.Remove(vk))
                KeyUpInternal(vk);
            else
                KeyUpInternal(vk);
        }
        return true;
    }

    public static void ReleaseAllHeldKeys()
    {
        lock (HeldKeys)
        {
            foreach (var vk in HeldKeys.ToArray())
                KeyUpInternal(vk);
            HeldKeys.Clear();
        }
    }

    public static void Hotkey(IReadOnlyList<string> parts)
    {
        if (parts.Count == 0) return;

        var temporaryModifiers = new List<ushort>();
        for (var i = 0; i < parts.Count - 1; i++)
        {
            var mod = ResolveKey(parts[i]);
            if (mod == 0) continue;

            lock (HeldKeys)
            {
                if (!HeldKeys.Contains(mod))
                {
                    KeyDownInternal(mod);
                    temporaryModifiers.Add(mod);
                }
            }
        }

        var last = ResolveKey(parts[^1]);
        if (last != 0)
        {
            KeyDownInternal(last);
            KeyUpInternal(last);
        }

        for (var i = temporaryModifiers.Count - 1; i >= 0; i--)
            KeyUpInternal(temporaryModifiers[i]);
    }

    public static void TypeUnicode(string text)
    {
        foreach (var ch in text)
        {
            var inputs = new[]
            {
                new INPUT
                {
                    type = InputKeyboard,
                    U = new InputUnion
                    {
                        ki = new KEYBDINPUT
                        {
                            wVk = 0,
                            wScan = ch,
                            dwFlags = KeyEventUnicode,
                            time = 0,
                            dwExtraInfo = IntPtr.Zero
                        }
                    }
                },
                new INPUT
                {
                    type = InputKeyboard,
                    U = new InputUnion
                    {
                        ki = new KEYBDINPUT
                        {
                            wVk = 0,
                            wScan = ch,
                            dwFlags = KeyEventUnicode | KeyEventKeyUp,
                            time = 0,
                            dwExtraInfo = IntPtr.Zero
                        }
                    }
                }
            };

            SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        }
    }

    private static ushort ResolveKey(string keyName)
    {
        var key = keyName.Trim().ToUpperInvariant();

        if (key.Length == 1)
        {
            var ch = key[0];
            if (ch is >= 'A' and <= 'Z') return ch;
            if (ch is >= '0' and <= '9') return ch;
        }

        return key switch
        {
            "CTRL" or "CONTROL" => 0x11,
            "SHIFT" => 0x10,
            "ALT" => 0x12,
            "WIN" or "WINDOWS" => 0x5B,
            "ENTER" or "RETURN" => 0x0D,
            "BACKSPACE" => 0x08,
            "TAB" => 0x09,
            "ESC" or "ESCAPE" => 0x1B,
            "SPACE" => 0x20,
            "DELETE" or "DEL" => 0x2E,
            "HOME" => 0x24,
            "END" => 0x23,
            "PAGEUP" => 0x21,
            "PAGEDOWN" => 0x22,
            "LEFT" => 0x25,
            "UP" => 0x26,
            "RIGHT" => 0x27,
            "DOWN" => 0x28,
            "F1" => 0x70,
            "F2" => 0x71,
            "F3" => 0x72,
            "F4" => 0x73,
            "F5" => 0x74,
            "F6" => 0x75,
            "F7" => 0x76,
            "F8" => 0x77,
            "F9" => 0x78,
            "F10" => 0x79,
            "F11" => 0x7A,
            "F12" => 0x7B,
            _ => 0
        };
    }

    private static void SendMouse(int dx, int dy, int data, uint flags)
    {
        var input = new INPUT
        {
            type = InputMouse,
            U = new InputUnion
            {
                mi = new MOUSEINPUT
                {
                    dx = dx,
                    dy = dy,
                    mouseData = data,
                    dwFlags = flags,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };

        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void KeyDownInternal(ushort vk)
    {
        var input = new INPUT
        {
            type = InputKeyboard,
            U = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = vk,
                    wScan = 0,
                    dwFlags = 0,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };

        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void KeyUpInternal(ushort vk)
    {
        var input = new INPUT
        {
            type = InputKeyboard,
            U = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = vk,
                    wScan = 0,
                    dwFlags = KeyEventKeyUp,
                    time = 0,
                    dwExtraInfo = IntPtr.Zero
                }
            }
        };

        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public int type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public int mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }
}
