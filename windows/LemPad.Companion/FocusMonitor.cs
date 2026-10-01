using System.Windows.Automation;

namespace LemPad.Companion;

internal sealed class FocusMonitor
{
    private bool _lastEditable;

    public event Action<bool>? EditableFocusChanged;

    public async Task RunAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            var editable = IsEditableFocused();
            if (editable != _lastEditable)
            {
                _lastEditable = editable;
                EditableFocusChanged?.Invoke(editable);
            }

            try
            {
                await Task.Delay(220, token);
            }
            catch (TaskCanceledException)
            {
                break;
            }
        }
    }

    private static bool IsEditableFocused()
    {
        try
        {
            var element = AutomationElement.FocusedElement;
            if (element is null) return false;

            var controlType = element.Current.ControlType;
            var keyboardFocusable = element.Current.IsKeyboardFocusable;

            if (controlType == ControlType.Edit)
            {
                return keyboardFocusable;
            }

            if (controlType == ControlType.Document && keyboardFocusable)
            {
                return element.TryGetCurrentPattern(TextPattern.Pattern, out _) ||
                       element.TryGetCurrentPattern(ValuePattern.Pattern, out _);
            }

            if (element.TryGetCurrentPattern(ValuePattern.Pattern, out var patternObj) &&
                patternObj is ValuePattern valuePattern)
            {
                return keyboardFocusable && !valuePattern.Current.IsReadOnly;
            }
        }
        catch
        {
            // Some elevated or protected windows do not expose UI Automation data.
        }

        return false;
    }
}
