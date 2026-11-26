using System;
using System.Drawing;

internal static class ToastPlacement
{
    internal const int MaxVisible = 3;

    internal static int Scale(int value, int dpi)
    {
        return (int)Math.Round(value * dpi / 96.0);
    }

    internal static int Duration(string body)
    {
        return Math.Max(7500, Math.Min(13500, 5300 + body.Length * 32));
    }

    internal static int Width(Rectangle area, int dpi)
    {
        return Math.Min(Scale(360, dpi), Math.Max(1, area.Width - 2 * Scale(15, dpi)));
    }

    internal static Rectangle At(Rectangle area, Size size, int previousHeight, int dpi)
    {
        int margin = Scale(15, dpi);
        return new Rectangle(area.Right - margin - size.Width, area.Top + margin + previousHeight,
                size.Width, size.Height);
    }

    internal static int Gap(int dpi) { return Scale(9, dpi); }

    internal static bool Fits(Rectangle area, Rectangle bounds, int dpi)
    {
        return bounds.Width <= area.Width && bounds.Bottom <= area.Bottom - Scale(15, dpi);
    }
}
