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

    private const ushort VkControl = 0x11;
    private const ushort VkShift = 0x10;
    private const ushort VkMenu = 0x12;
    private const ushort VkLWin = 0x5B;
    private const ushort VkReturn = 0x0D;
    private const ushort VkBack = 0x08;
    private const ushort VkTab = 0x09;
    private const ushort VkEscape = 0x1B;
    private const ushort VkDelete = 0x2E;
    private const ushort VkLeft = 0x25;
    private const ushort VkUp = 0x26;
    private const ushort VkRight = 0x27;
    private const ushort VkDown = 0x28;

    public static void Move(int dx, int dy) =>
        SendMouse(dx, dy, 0, MouseMove);

    public static void Scroll(int delta) =>
        SendMouse(0, 0, delta, MouseWheel);

    public static void ClickLeft()
    {
        SendMouse(0, 0, 0, MouseLeftDown);
        SendMouse(0, 0, 0, MouseLeftUp);
    }

    public static void ClickRight()
    {
        SendMouse(0, 0, 0, MouseRightDown);
        SendMouse(0, 0, 0, MouseRightUp);
    }

    public static void MouseDown(string button) =>
        SendMouse(0, 0, 0, button.Equals("RIGHT", StringComparison.OrdinalIgnoreCase) ? MouseRightDown : MouseLeftDown);

    public static void MouseUp(string button) =>
        SendMouse(0, 0, 0, button.Equals("RIGHT", StringComparison.OrdinalIgnoreCase) ? MouseRightUp : MouseLeftUp);

    public static void PressKey(string keyName)
    {
        var vk = keyName.ToUpperInvariant() switch
        {
            "ENTER" => VkReturn,
            "BACKSPACE" => VkBack,
            "TAB" => VkTab,
            "ESC" or "ESCAPE" => VkEscape,
            "DELETE" => VkDelete,
            "LEFT" => VkLeft,
            "RIGHT" => VkRight,
            "UP" => VkUp,
            "DOWN" => VkDown,
            _ => (ushort)0
        };

        if (vk != 0)
        {
            KeyDown(vk);
            KeyUp(vk);
        }
    }

    public static void Hotkey(IReadOnlyList<string> parts)
    {
        if (parts.Count == 0) return;

        var modifiers = new List<ushort>();
        for (var i = 0; i < parts.Count - 1; i++)
        {
            var mod = parts[i].ToUpperInvariant() switch
            {
                "CTRL" or "CONTROL" => VkControl,
                "SHIFT" => VkShift,
                "ALT" => VkMenu,
                "WIN" or "WINDOWS" => VkLWin,
                _ => (ushort)0
            };
            if (mod != 0) modifiers.Add(mod);
        }

        foreach (var mod in modifiers) KeyDown(mod);

        var last = parts[^1];
        if (last.Length == 1)
        {
            var ch = char.ToUpperInvariant(last[0]);
            KeyDown(ch);
            KeyUp(ch);
        }
        else
        {
            PressKey(last);
        }

        for (var i = modifiers.Count - 1; i >= 0; i--) KeyUp(modifiers[i]);
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

    private static void KeyDown(ushort vk)
    {
        var input = new INPUT
        {
            type = InputKeyboard,
            U = new InputUnion
            {
                ki = new KEYBDINPUT { wVk = vk, wScan = 0, dwFlags = 0, time = 0, dwExtraInfo = IntPtr.Zero }
            }
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void KeyUp(ushort vk)
    {
        var input = new INPUT
        {
            type = InputKeyboard,
            U = new InputUnion
            {
                ki = new KEYBDINPUT { wVk = vk, wScan = 0, dwFlags = KeyEventKeyUp, time = 0, dwExtraInfo = IntPtr.Zero }
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
