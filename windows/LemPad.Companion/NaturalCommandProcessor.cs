using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Security;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;

namespace LemPad.Companion;

internal sealed class NaturalCommandProcessor
{
    private string? _currentPath;
    private bool _browserContext;

    public async Task<CommandResult> ProcessAsync(string rawText, bool editableFocus)
    {
        var raw = rawText.Trim();
        if (string.IsNullOrWhiteSpace(raw))
            return CommandResult.NotHandled();

        var segments = SplitCommands(raw);
        var handledAny = false;
        var statuses = new List<string>();
        var modifierChanges = new List<ModifierChange>();

        foreach (var segment in segments)
        {
            var result = await ProcessSingleAsync(segment.Trim());
            if (!result.Handled) continue;

            handledAny = true;
            if (!string.IsNullOrWhiteSpace(result.Status))
                statuses.Add(result.Status);
            modifierChanges.AddRange(result.ModifierChanges);
        }

        if (!handledAny && editableFocus)
        {
            InputInjector.TypeUnicode(raw);
            return new CommandResult(true, "متن تایپ شد", []);
        }

        if (!handledAny)
            return new CommandResult(false, "فرمان را متوجه نشدم", []);

        return new CommandResult(
            true,
            string.Join(" • ", statuses.Distinct()),
            modifierChanges
        );
    }

    private async Task<CommandResult> ProcessSingleAsync(string raw)
    {
        var text = Normalize(raw);
        if (string.IsNullOrWhiteSpace(text))
            return CommandResult.NotHandled();

        var modifier = TryModifierCommand(text);
        if (modifier.Handled) return modifier;

        var browser = TryBrowserCommand(text);
        if (browser.Handled) return browser;

        var windows = TryWindowCommand(text);
        if (windows.Handled) return windows;

        var key = TryKeyboardCommand(text);
        if (key.Handled) return key;

        var files = TryFileExplorerCommand(text);
        if (files.Handled) return files;

        var ping = TryPingCommand(text);
        if (ping.Handled) return ping;

        if (ContainsAny(text, "آی پی سیستم", "ای پی سیستم", "ip سیستم", "ip رو نشون", "آی پی رو نشون"))
        {
            StartProcess("cmd.exe", ["/k", "ipconfig"]);
            return CommandResult.Done("IP سیستم نمایش داده شد");
        }

        if (ContainsAny(text, "تسک منیجر", "task manager"))
        {
            StartProcess("taskmgr.exe");
            return CommandResult.Done("Task Manager باز شد");
        }

        if (ContainsAny(text, "سی ام دی", "cmd", "کامند پرامپت"))
        {
            StartProcess("cmd.exe");
            return CommandResult.Done("CMD باز شد");
        }

        var adapter = await TryNetworkAdapterCommandAsync(raw, text);
        if (adapter.Handled) return adapter;

        var launch = TryLaunchAppCommand(raw, text);
        if (launch.Handled) return launch;

        var url = TryDirectUrl(text);
        if (url.Handled) return url;

        return CommandResult.NotHandled();
    }

    private static CommandResult TryModifierCommand(string text)
    {
        var modifiers = new Dictionary<string, string>
        {
            ["کنترل"] = "CTRL",
            ["کنترول"] = "CTRL",
            ["ctrl"] = "CTRL",
            ["شیفت"] = "SHIFT",
            ["shift"] = "SHIFT",
            ["آلت"] = "ALT",
            ["الت"] = "ALT",
            ["alt"] = "ALT",
            ["ویندوز"] = "WIN",
            ["windows"] = "WIN",
            ["win"] = "WIN"
        };

        var selected = modifiers.FirstOrDefault(pair => ContainsWord(text, pair.Key));
        if (string.IsNullOrWhiteSpace(selected.Value))
            return CommandResult.NotHandled();

        var key = selected.Value;
        var label = key switch
        {
            "CTRL" => "Ctrl",
            "SHIFT" => "Shift",
            "ALT" => "Alt",
            "WIN" => "Windows",
            _ => key
        };

        if (ContainsAny(text, "نگه دار", "نگهدار", "پایین نگه", "hold"))
        {
            InputInjector.HoldKey(key);
            return new CommandResult(
                true,
                label + " نگه داشته شد",
                [new ModifierChange(key, true)]
            );
        }

        if (ContainsAny(text, "ول کن", "رها کن", "آزاد کن", "release"))
        {
            InputInjector.ReleaseKey(key);
            return new CommandResult(
                true,
                label + " آزاد شد",
                [new ModifierChange(key, false)]
            );
        }

        var combo = TryKnownCombo(text);
        if (combo.Handled) return combo;

        if (ContainsAny(text, "رو بزن", "را بزن", "بزن", "press") &&
            !ContainsAny(text, "باز کن", "اجرا کن"))
        {
            InputInjector.PressKey(key);
            return CommandResult.Done(label + " زده شد");
        }

        return CommandResult.NotHandled();
    }

    private static CommandResult TryKnownCombo(string text)
    {
        if ((ContainsWord(text, "کنترل") || ContainsWord(text, "ctrl")) &&
            (ContainsWord(text, "سی") || Regex.IsMatch(text, @"\bc\b")))
        {
            InputInjector.Hotkey(["CTRL", "C"]);
            return CommandResult.Done("کپی");
        }

        if ((ContainsWord(text, "کنترل") || ContainsWord(text, "ctrl")) &&
            (ContainsWord(text, "وی") || Regex.IsMatch(text, @"\bv\b")))
        {
            InputInjector.Hotkey(["CTRL", "V"]);
            return CommandResult.Done("پیست");
        }

        if ((ContainsWord(text, "کنترل") || ContainsWord(text, "ctrl")) &&
            (ContainsWord(text, "آ") || ContainsWord(text, "ای") || Regex.IsMatch(text, @"\ba\b")))
        {
            InputInjector.Hotkey(["CTRL", "A"]);
            return CommandResult.Done("همه انتخاب شد");
        }

        if ((ContainsWord(text, "شیفت") || ContainsWord(text, "shift")) &&
            ContainsAny(text, "اینتر", "enter"))
        {
            InputInjector.Hotkey(["SHIFT", "ENTER"]);
            return CommandResult.Done("Shift+Enter");
        }

        if ((ContainsWord(text, "آلت") || ContainsWord(text, "الت") || ContainsWord(text, "alt")) &&
            ContainsAny(text, "تب", "tab"))
        {
            InputInjector.Hotkey(["ALT", "TAB"]);
            return CommandResult.Done("Alt+Tab");
        }

        return CommandResult.NotHandled();
    }

    private static CommandResult TryKeyboardCommand(string text)
    {
        if (ContainsAny(text, "کپی کن", "کپی", "copy"))
        {
            InputInjector.Hotkey(["CTRL", "C"]);
            return CommandResult.Done("کپی");
        }

        if (ContainsAny(text, "پیست کن", "پیست", "paste"))
        {
            InputInjector.Hotkey(["CTRL", "V"]);
            return CommandResult.Done("پیست");
        }

        if (ContainsAny(text, "همه رو انتخاب کن", "همه را انتخاب کن", "select all"))
        {
            InputInjector.Hotkey(["CTRL", "A"]);
            return CommandResult.Done("همه انتخاب شد");
        }

        var keys = new (string[] Words, string Key, string Label)[]
        {
            (["اینتر", "enter"], "ENTER", "Enter"),
            (["اسپیس", "فاصله", "space"], "SPACE", "Space"),
            (["بک اسپیس", "بک‌اسپیس", "backspace", "پاک کن"], "BACKSPACE", "Backspace"),
            (["اسکیپ", "escape", "esc"], "ESC", "Esc"),
            (["دیلیت", "delete"], "DELETE", "Delete"),
            (["تب", "tab"], "TAB", "Tab"),
            (["فلش بالا"], "UP", "↑"),
            (["فلش پایین"], "DOWN", "↓"),
            (["فلش چپ"], "LEFT", "←"),
            (["فلش راست"], "RIGHT", "→")
        };

        foreach (var item in keys)
        {
            if (!ContainsAny(text, item.Words)) continue;
            InputInjector.PressKey(item.Key);
            return CommandResult.Done(item.Label);
        }

        return CommandResult.NotHandled();
    }

    private CommandResult TryBrowserCommand(string text)
    {
        if (ContainsAny(text, "کروم رو باز کن", "کروم را باز کن", "گوگل کروم رو باز کن",
                "گوگل کروم را باز کن", "chrome رو باز کن", "chrome را باز کن",
                "کروم رو اجرا کن", "کروم را اجرا کن", "chrome رو اجرا کن"))
        {
            if (LaunchKnown("chrome.exe", "chrome"))
            {
                _browserContext = true;
                return CommandResult.Done("Chrome باز شد");
            }
        }

        if (ContainsAny(text, "یه تب جدید", "یک تب جدید", "تب جدید", "new tab"))
        {
            InputInjector.Hotkey(["CTRL", "T"]);
            _browserContext = true;
            return CommandResult.Done("تب جدید باز شد");
        }

        if (ContainsAny(text, "این تب رو ببند", "تب رو ببند", "تب را ببند", "close tab"))
        {
            InputInjector.Hotkey(["CTRL", "W"]);
            return CommandResult.Done("تب بسته شد");
        }

        if (ContainsAny(text, "برگرد صفحه قبل", "برگرد عقب صفحه", "browser back"))
        {
            InputInjector.Hotkey(["ALT", "LEFT"]);
            return CommandResult.Done("صفحه قبل");
        }

        if (ContainsAny(text, "صفحه بعد", "browser forward"))
        {
            InputInjector.Hotkey(["ALT", "RIGHT"]);
            return CommandResult.Done("صفحه بعد");
        }

        if (ContainsAny(text, "رفرش کن", "تازه سازی کن", "تازه‌سازی کن", "refresh"))
        {
            InputInjector.Hotkey(["CTRL", "R"]);
            return CommandResult.Done("صفحه تازه شد");
        }

        if (ContainsAny(text, "برو گوگل", "گوگل رو باز کن", "google رو باز کن", "google.com",
                "گوگل دات کام"))
        {
            NavigateTo("https://www.google.com");
            return CommandResult.Done("Google باز شد");
        }

        if (ContainsAny(text, "برو یوتیوب", "یوتیوب رو باز کن", "youtube رو باز کن", "youtube.com",
                "یوتیوب دات کام"))
        {
            NavigateTo("https://www.youtube.com");
            return CommandResult.Done("YouTube باز شد");
        }

        return CommandResult.NotHandled();
    }

    private CommandResult TryWindowCommand(string text)
    {
        if (ContainsAny(text, "مینیمایز کن", "کوچیکش کن", "کوچکش کن", "minimize"))
        {
            var hwnd = GetForegroundWindow();
            if (hwnd != IntPtr.Zero) ShowWindow(hwnd, 6);
            return CommandResult.Done("پنجره Minimize شد");
        }

        if (ContainsAny(text, "مکسیمایز کن", "تمام صفحه کن", "maximize"))
        {
            var hwnd = GetForegroundWindow();
            if (hwnd != IntPtr.Zero) ShowWindow(hwnd, 3);
            return CommandResult.Done("پنجره Maximize شد");
        }

        if (ContainsAny(text, "پنجره رو ببند", "پنجره را ببند", "کل پنجره رو ببند", "close window"))
        {
            InputInjector.Hotkey(["ALT", "F4"]);
            return CommandResult.Done("پنجره بسته شد");
        }

        if (ContainsAny(text, "برو تو استارت", "استارت منو", "منوی استارت", "start menu"))
        {
            InputInjector.PressKey("WIN");
            return CommandResult.Done("Start باز شد");
        }

        return CommandResult.NotHandled();
    }

    private CommandResult TryFileExplorerCommand(string text)
    {
        if (ContainsAny(text, "مای کامپیوتر", "مای کامپیوترم", "this pc", "دیس پی سی", "دیس پی‌سی"))
        {
            StartProcess("explorer.exe", ["shell:MyComputerFolder"]);
            _currentPath = null;
            return CommandResult.Done("This PC باز شد");
        }

        var driveMatch = Regex.Match(text, @"(?:درایو|drive)\s*([a-z])\b", RegexOptions.IgnoreCase);
        if (driveMatch.Success)
        {
            var root = char.ToUpperInvariant(driveMatch.Groups[1].Value[0]) + @":\";
            if (Directory.Exists(root))
            {
                StartProcess("explorer.exe", [root]);
                _currentPath = root;
                return CommandResult.Done("درایو " + char.ToUpperInvariant(driveMatch.Groups[1].Value[0]) + " باز شد");
            }

            return CommandResult.Done("این درایو پیدا نشد");
        }

        var folderMatch = Regex.Match(text, @"(?:برو\s+)?(?:تو\s+)?پوشه\s+(.+?)(?:\s+رو\s+باز\s+کن|\s+را\s+باز\s+کن|$)",
            RegexOptions.IgnoreCase);
        if (folderMatch.Success)
        {
            var folderName = CleanObjectName(folderMatch.Groups[1].Value);
            var resolved = ResolveFolder(folderName);
            if (resolved is not null)
            {
                StartProcess("explorer.exe", [resolved]);
                _currentPath = resolved;
                return CommandResult.Done("پوشه " + folderName + " باز شد");
            }

            return CommandResult.Done("پوشه " + folderName + " پیدا نشد");
        }

        if (ContainsAny(text, "یه پوشه برو بالا", "یک پوشه برو بالا", "پوشه بالاتر", "برو بالا"))
        {
            InputInjector.Hotkey(["ALT", "UP"]);
            if (_currentPath is not null)
                _currentPath = Directory.GetParent(_currentPath)?.FullName;
            return CommandResult.Done("یک پوشه بالاتر");
        }

        if (ContainsAny(text, "برگرد عقب", "برگرد قبلی"))
        {
            InputInjector.Hotkey(["ALT", "LEFT"]);
            return CommandResult.Done("برگشت");
        }

        if (ContainsAny(text, "این فایل رو باز کن", "این فایل را باز کن", "بازش کن"))
        {
            InputInjector.PressKey("ENTER");
            return CommandResult.Done("مورد انتخاب‌شده باز شد");
        }

        return CommandResult.NotHandled();
    }

    private static CommandResult TryPingCommand(string text)
    {
        if (!ContainsAny(text, "پینگ", "ping"))
            return CommandResult.NotHandled();

        var host = "google.com";
        var ipv4 = Regex.Match(text, @"\b(?:\d{1,3}\.){3}\d{1,3}\b");
        if (ipv4.Success)
        {
            host = ipv4.Value;
        }
        else
        {
            var domain = Regex.Match(text, @"\b[a-z0-9][a-z0-9.-]+\.[a-z]{2,}\b",
                RegexOptions.IgnoreCase);
            if (domain.Success)
                host = domain.Value;
            else if (ContainsAny(text, "گوگل", "google"))
                host = "google.com";
        }

        StartProcess("cmd.exe", ["/k", "ping", host]);
        return CommandResult.Done("Ping " + host + " شروع شد");
    }

    private async Task<CommandResult> TryNetworkAdapterCommandAsync(string raw, string text)
    {
        var wifiConnect = Regex.Match(
            raw,
            @"(?:به\s+)?وای[\s‌-]*فای\s+(.+?)\s+(?:با\s+)?رمز\s+(.+?)\s+(?:وصل\s+شو|وصل\s+کن)$",
            RegexOptions.IgnoreCase
        );

        if (wifiConnect.Success)
        {
            var ssid = wifiConnect.Groups[1].Value.Trim();
            var password = wifiConnect.Groups[2].Value.Trim();
            var success = await ConnectWifiAsync(ssid, password);
            return CommandResult.Done(success
                ? "اتصال به وای‌فای " + ssid + " انجام شد"
                : "اتصال به وای‌فای انجام نشد");
        }

        if (ContainsAny(text, "وای فای رو روشن کن", "وایفای رو روشن کن", "wifi رو روشن کن"))
            return await SetAdapterStateAsync(NetworkInterfaceType.Wireless80211, true, "Wi‑Fi");

        if (ContainsAny(text, "وای فای رو خاموش کن", "وایفای رو خاموش کن", "wifi رو خاموش کن"))
            return await SetAdapterStateAsync(NetworkInterfaceType.Wireless80211, false, "Wi‑Fi");

        if (ContainsAny(text, "لن رو خاموش کن", "lan رو خاموش کن", "اترنت رو خاموش کن"))
            return await SetEthernetStateAsync(false);

        if (ContainsAny(text, "لن رو روشن کن", "lan رو روشن کن", "اترنت رو روشن کن"))
            return await SetEthernetStateAsync(true);

        return CommandResult.NotHandled();
    }

    private CommandResult TryLaunchAppCommand(string raw, string text)
    {
        if (!ContainsAny(text, "باز کن", "اجرا کن", "ران کن", "run"))
            return CommandResult.NotHandled();

        var aliases = new Dictionary<string, string[]>(StringComparer.OrdinalIgnoreCase)
        {
            ["استیم"] = ["steam.exe", "Steam"],
            ["steam"] = ["steam.exe", "Steam"],
            ["فتوشاپ"] = ["Photoshop"],
            ["photoshop"] = ["Photoshop"],
            ["نوت پد"] = ["notepad.exe", "Notepad"],
            ["notepad"] = ["notepad.exe", "Notepad"],
            ["اکسپلورر"] = ["explorer.exe", "File Explorer"],
            ["فایل اکسپلورر"] = ["explorer.exe", "File Explorer"],
            ["کلکولیتر"] = ["calc.exe", "Calculator"],
            ["ماشین حساب"] = ["calc.exe", "Calculator"]
        };

        foreach (var alias in aliases)
        {
            if (!text.Contains(alias.Key, StringComparison.OrdinalIgnoreCase)) continue;
            if (LaunchKnown(alias.Value))
                return CommandResult.Done(alias.Key + " اجرا شد");
        }

        var name = ExtractLaunchTarget(raw);
        if (string.IsNullOrWhiteSpace(name))
            return CommandResult.NotHandled();

        if (TryFindAndLaunch(name))
            return CommandResult.Done(name + " اجرا شد");

        return CommandResult.Done(name + " پیدا نشد");
    }

    private CommandResult TryDirectUrl(string text)
    {
        var latin = text
            .Replace(" دات ", ".", StringComparison.OrdinalIgnoreCase)
            .Replace(" نقطه ", ".", StringComparison.OrdinalIgnoreCase)
            .Replace(" ", "");

        var match = Regex.Match(latin, @"(?:https?://)?(?:www\.)?[a-z0-9][a-z0-9.-]+\.[a-z]{2,}(?:/\S*)?",
            RegexOptions.IgnoreCase);

        if (!match.Success)
            return CommandResult.NotHandled();

        var url = match.Value.StartsWith("http", StringComparison.OrdinalIgnoreCase)
            ? match.Value
            : "https://" + match.Value;

        NavigateTo(url);
        return CommandResult.Done("آدرس باز شد");
    }

    private void NavigateTo(string url)
    {
        if (_browserContext)
        {
            InputInjector.Hotkey(["CTRL", "L"]);
            InputInjector.TypeUnicode(url);
            InputInjector.PressKey("ENTER");
        }
        else
        {
            StartProcess(url);
            _browserContext = true;
        }
    }

    private string? ResolveFolder(string folderName)
    {
        var special = Normalize(folderName);
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

        if (ContainsAny(special, "دانلود", "downloads"))
        {
            var downloads = Path.Combine(home, "Downloads");
            if (Directory.Exists(downloads)) return downloads;
        }

        if (ContainsAny(special, "دسکتاپ", "desktop"))
        {
            var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            if (Directory.Exists(desktop)) return desktop;
        }

        if (ContainsAny(special, "داکیومنت", "documents", "اسناد"))
        {
            var docs = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
            if (Directory.Exists(docs)) return docs;
        }

        var roots = new List<string>();
        if (_currentPath is not null && Directory.Exists(_currentPath))
            roots.Add(_currentPath);
        roots.Add(home);

        foreach (var root in roots.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                var hit = Directory.EnumerateDirectories(root)
                    .FirstOrDefault(p => Path.GetFileName(p)
                        .Contains(folderName, StringComparison.OrdinalIgnoreCase));
                if (hit is not null) return hit;
            }
            catch
            {
            }
        }

        return null;
    }

    private bool TryFindAndLaunch(string query)
    {
        var candidates = new List<string>();

        var commonStart = Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu);
        var userStart = Environment.GetFolderPath(Environment.SpecialFolder.StartMenu);
        var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        var commonDesktop = Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory);

        candidates.AddRange(FindMatches(commonStart, query, ["*.lnk", "*.exe"], 3000));
        candidates.AddRange(FindMatches(userStart, query, ["*.lnk", "*.exe"], 3000));
        candidates.AddRange(FindMatches(desktop, query, ["*.lnk", "*.exe"], 1000));
        candidates.AddRange(FindMatches(commonDesktop, query, ["*.lnk", "*.exe"], 1000));

        if (_currentPath is not null)
            candidates.AddRange(FindMatches(_currentPath, query, ["*.exe", "*.lnk"], 5000));

        var match = candidates.FirstOrDefault();
        if (match is null) return false;

        StartProcess(match);
        return true;
    }

    private static IReadOnlyList<string> FindMatches(
        string root,
        string query,
        IReadOnlyList<string> patterns,
        int maxItems)
    {
        var results = new List<string>();
        if (string.IsNullOrWhiteSpace(root) || !Directory.Exists(root))
            return results;

        var normalizedQuery = NormalizeForMatch(query);
        var count = 0;

        foreach (var pattern in patterns)
        {
            try
            {
                foreach (var path in Directory.EnumerateFiles(root, pattern, SearchOption.AllDirectories))
                {
                    if (++count > maxItems)
                        return results;

                    var file = NormalizeForMatch(Path.GetFileNameWithoutExtension(path));
                    if (file.Contains(normalizedQuery, StringComparison.OrdinalIgnoreCase) ||
                        normalizedQuery.Contains(file, StringComparison.OrdinalIgnoreCase))
                    {
                        results.Add(path);
                    }
                }
            }
            catch
            {
                // Skip protected or inaccessible folders.
            }
        }

        return results;
    }

    private static bool LaunchKnown(params string[] names)
    {
        foreach (var name in names)
        {
            try
            {
                StartProcess(name);
                return true;
            }
            catch
            {
            }
        }

        return false;
    }

    private static async Task<CommandResult> SetAdapterStateAsync(
        NetworkInterfaceType type,
        bool enabled,
        string label)
    {
        var adapter = NetworkInterface.GetAllNetworkInterfaces()
            .FirstOrDefault(n => n.NetworkInterfaceType == type);

        if (adapter is null)
            return CommandResult.Done(label + " پیدا نشد");

        return await SetAdapterByNameAsync(adapter.Name, enabled, label);
    }

    private static async Task<CommandResult> SetEthernetStateAsync(bool enabled)
    {
        var adapter = NetworkInterface.GetAllNetworkInterfaces()
            .FirstOrDefault(n => n.NetworkInterfaceType is
                NetworkInterfaceType.Ethernet or
                NetworkInterfaceType.GigabitEthernet or
                NetworkInterfaceType.FastEthernetFx or
                NetworkInterfaceType.FastEthernetT);

        if (adapter is null)
            return CommandResult.Done("LAN پیدا نشد");

        return await SetAdapterByNameAsync(adapter.Name, enabled, "LAN");
    }

    private static async Task<CommandResult> SetAdapterByNameAsync(
        string adapterName,
        bool enabled,
        string label)
    {
        if (!IsAdministrator())
            return CommandResult.Done("برای تغییر " + label + "، Companion را با Run as administrator اجرا کن");

        var result = await RunHiddenAsync(
            "netsh",
            ["interface", "set", "interface", "name=" + adapterName,
                "admin=" + (enabled ? "enabled" : "disabled")]
        );

        return CommandResult.Done(result == 0
            ? label + (enabled ? " روشن شد" : " خاموش شد")
            : "تغییر وضعیت " + label + " انجام نشد");
    }

    private static async Task<bool> ConnectWifiAsync(string ssid, string password)
    {
        if (string.IsNullOrWhiteSpace(ssid) || string.IsNullOrWhiteSpace(password))
            return false;

        var escapedSsid = SecurityElement.Escape(ssid) ?? ssid;
        var escapedPassword = SecurityElement.Escape(password) ?? password;
        var profileName = "LemPad-" + Guid.NewGuid().ToString("N");
        var file = Path.Combine(Path.GetTempPath(), profileName + ".xml");

        var xml = $@"<?xml version=""1.0""?>
<WLANProfile xmlns=""http://www.microsoft.com/networking/WLAN/profile/v1"">
  <name>{escapedSsid}</name>
  <SSIDConfig><SSID><name>{escapedSsid}</name></SSID></SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>auto</connectionMode>
  <MSM><security>
    <authEncryption>
      <authentication>WPA2PSK</authentication>
      <encryption>AES</encryption>
      <useOneX>false</useOneX>
    </authEncryption>
    <sharedKey>
      <keyType>passPhrase</keyType>
      <protected>false</protected>
      <keyMaterial>{escapedPassword}</keyMaterial>
    </sharedKey>
  </security></MSM>
</WLANProfile>";

        try
        {
            await File.WriteAllTextAsync(file, xml, new UTF8Encoding(false));
            var add = await RunHiddenAsync("netsh", ["wlan", "add", "profile", "filename=" + file, "user=current"]);
            if (add != 0) return false;

            var connect = await RunHiddenAsync("netsh", ["wlan", "connect", "name=" + ssid, "ssid=" + ssid]);
            return connect == 0;
        }
        catch
        {
            return false;
        }
        finally
        {
            try { File.Delete(file); } catch { }
        }
    }

    private static async Task<int> RunHiddenAsync(string file, IReadOnlyList<string> args)
    {
        try
        {
            var info = new ProcessStartInfo(file)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };

            foreach (var arg in args)
                info.ArgumentList.Add(arg);

            using var process = Process.Start(info);
            if (process is null) return -1;
            await process.WaitForExitAsync();
            return process.ExitCode;
        }
        catch
        {
            return -1;
        }
    }

    private static void StartProcess(string file, IReadOnlyList<string>? args = null)
    {
        var info = new ProcessStartInfo(file)
        {
            UseShellExecute = true
        };

        if (args is not null)
        {
            foreach (var arg in args)
                info.ArgumentList.Add(arg);
        }

        Process.Start(info);
    }

    private static string ExtractLaunchTarget(string raw)
    {
        var result = Regex.Replace(raw, @"\b(?:لطفا|لطفاً)\b", "", RegexOptions.IgnoreCase);
        result = Regex.Replace(result, @"\s*(?:رو|را)?\s*(?:باز\s+کن|اجرا\s+کن|ران\s+کن|run)\s*$",
            "", RegexOptions.IgnoreCase);
        result = Regex.Replace(result, @"^(?:برو\s+)?", "", RegexOptions.IgnoreCase);
        return result.Trim(' ', '،', '.', '!');
    }

    private static string CleanObjectName(string text) =>
        Regex.Replace(text, @"\s+(?:رو|را)$", "", RegexOptions.IgnoreCase)
            .Trim(' ', '،', '.', '!');

    private static IReadOnlyList<string> SplitCommands(string raw)
    {
        var prepared = raw
            .Replace("،", ",")
            .Replace("؛", ",")
            .Replace(" و بعد ", ",", StringComparison.OrdinalIgnoreCase)
            .Replace(" بعد ", ",", StringComparison.OrdinalIgnoreCase)
            .Replace(" سپس ", ",", StringComparison.OrdinalIgnoreCase);

        prepared = Regex.Replace(
            prepared,
            @"\s+و\s+(?=(?:برو|یه|یک|تب|پنجره|این|یوتیوب|گوگل|کروم|مای|درایو|پوشه|کپی|پیست|مینیمایز|مکسیمایز|ببند|پینگ|اجرا|باز\s+کن))",
            ",",
            RegexOptions.IgnoreCase
        );

        return prepared.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    }

    private static string Normalize(string text)
    {
        var normalized = text
            .Replace('ي', 'ی')
            .Replace('ك', 'ک')
            .Replace("‌", " ")
            .Replace("ـ", "")
            .Trim()
            .ToLowerInvariant();

        return Regex.Replace(normalized, @"\s+", " ");
    }

    private static string NormalizeForMatch(string text)
    {
        var value = Normalize(text);
        return Regex.Replace(value, @"[^\p{L}\p{N}]+", "");
    }

    private static bool ContainsAny(string text, params string[] values) =>
        values.Any(value => text.Contains(Normalize(value), StringComparison.OrdinalIgnoreCase));

    private static bool ContainsAny(string text, IEnumerable<string> values) =>
        values.Any(value => text.Contains(Normalize(value), StringComparison.OrdinalIgnoreCase));

    private static bool ContainsWord(string text, string word)
    {
        var normalized = Normalize(word);
        if (normalized.All(ch => ch <= 127))
            return Regex.IsMatch(text, @"\b" + Regex.Escape(normalized) + @"\b", RegexOptions.IgnoreCase);

        return text.Contains(normalized, StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsAdministrator()
    {
        try
        {
            using var identity = WindowsIdentity.GetCurrent();
            return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
        catch
        {
            return false;
        }
    }

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}

internal sealed record ModifierChange(string Key, bool IsDown);

internal sealed record CommandResult(
    bool Handled,
    string Status,
    IReadOnlyList<ModifierChange> ModifierChanges)
{
    public static CommandResult Done(string status) => new(true, status, []);
    public static CommandResult NotHandled() => new(false, "", []);
}
