using System;
using System.Drawing;

internal static class ToastPlacementTest
{
    private static void Main()
    {
        foreach (int dpi in new[] { 96, 144, 192 })
        {
            Check(new Rectangle(0, 0, 1920, 1040), dpi, 3);
            Check(new Rectangle(-1366, -120, 1366, 768), dpi, dpi == 192 ? 2 : 3);
            Check(new Rectangle(0, 42, 800, 430), dpi, dpi == 192 ? 1 : dpi == 144 ? 2 : 3);
        }
        if (ToastPlacement.Duration("短通知") != 7500 || ToastPlacement.Duration(new string('成', 500)) != 13500)
            throw new Exception("展示时长");
        Console.WriteLine("PASS · Windows notice placement, stacking, small monitor and 100–200% DPI");
    }

    private static void Check(Rectangle area, int dpi, int expected)
    {
        int width = ToastPlacement.Width(area, dpi);
        int offset = 0, count = 0;
        Rectangle prior = Rectangle.Empty;
        for (int i = 0; i < ToastPlacement.MaxVisible; i++)
        {
            Size size = new Size(width, ToastPlacement.Scale(116, dpi));
            Rectangle next = ToastPlacement.At(area, size, offset, dpi);
            if (!ToastPlacement.Fits(area, next, dpi)) break;
            if (next.IntersectsWith(prior) || next.Right != area.Right - ToastPlacement.Scale(15, dpi)
                    || next.Left < area.Left || next.Top < area.Top)
                throw new Exception("通知重叠或越过工作区");
            offset += size.Height + ToastPlacement.Gap(dpi);
            prior = next;
            count++;
        }
        if (count != expected) throw new Exception("通知堆叠以外应排队");
    }
}
