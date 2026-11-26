using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;

internal sealed class DesktopNotices : IDisposable
{
    private sealed class Notice
    {
        internal readonly string Title, Text;
        internal readonly bool Sound;

        internal Notice(string title, string text, bool sound)
        {
            Title = title;
            Text = text;
            Sound = sound;
        }
    }

    private sealed class Popup : Form
    {
        private readonly Font headline, content, closeFont;
        internal long Expires;
        internal int HoverExtension;

        internal Popup(Notice notice, int width, int dpi, int maxHeight)
        {
            AutoScaleMode = AutoScaleMode.None;
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.Manual;
            TopMost = true;
            BackColor = Color.White;
            Text = notice.Title;
            AccessibleName = notice.Title;
            headline = new Font("Microsoft YaHei UI", 10.5f, FontStyle.Bold);
            content = new Font("Microsoft YaHei UI", 9f);
            closeFont = new Font("Segoe UI", 11f, FontStyle.Regular);
            int left = ToastPlacement.Scale(16, dpi);
            int top = ToastPlacement.Scale(11, dpi);
            int xWidth = ToastPlacement.Scale(32, dpi);
            int titleHeight = ToastPlacement.Scale(28, dpi);
            int bodyWidth = Math.Max(1, width - 2 * left);
            Size body = TextRenderer.MeasureText(notice.Text, content, new Size(bodyWidth - 2, Int32.MaxValue),
                    TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix);
            int bodyHeight = Math.Min(ToastPlacement.Scale(145, dpi),
                    Math.Max(ToastPlacement.Scale(36, dpi), body.Height + ToastPlacement.Scale(8, dpi)));
            Height = Math.Max(1, Math.Min(maxHeight, top + titleHeight + bodyHeight + top));
            Width = width;
            int usable = Math.Max(1, Height - top * 2 - titleHeight);

            var stripe = new Panel { BackColor = Color.FromArgb(12, 114, 96), Dock = DockStyle.Left,
                    Width = ToastPlacement.Scale(4, dpi) };
            var title = new Label { Text = notice.Title, Font = headline, UseMnemonic = false, AutoEllipsis = true,
                    ForeColor = Color.FromArgb(21, 49, 61),
                    Bounds = new Rectangle(left, top, Math.Max(1, width - left - xWidth), titleHeight) };
            var dismiss = new Button { Text = "×", FlatStyle = FlatStyle.Flat, Font = closeFont, TabStop = false,
                    AccessibleName = "关闭通知", ForeColor = Color.FromArgb(64, 88, 99),
                    Bounds = new Rectangle(width - xWidth, top / 2, xWidth, titleHeight) };
            dismiss.FlatAppearance.BorderSize = 0;
            dismiss.Click += (sender, args) => Close();
            var scroll = new Panel { AutoScroll = true, Bounds = new Rectangle(left, top + titleHeight, bodyWidth, usable) };
            var message = new Label { Text = notice.Text, Font = content, UseMnemonic = false,
                    ForeColor = Color.FromArgb(33, 58, 70),
                    Bounds = new Rectangle(0, 0, Math.Max(1, bodyWidth - (bodyHeight > usable ? SystemInformation.VerticalScrollBarWidth : 2)),
                        Math.Max(bodyHeight, body.Height + ToastPlacement.Scale(8, dpi))) };
            scroll.Controls.Add(message);
            Controls.AddRange(new Control[] { stripe, title, scroll, dismiss });
        }

        protected override bool ShowWithoutActivation { get { return true; } }

        protected override CreateParams CreateParams
        {
            get
            {
                var parameters = base.CreateParams;
                // Tool window and no activation, including while notifications arrive during data entry.
                parameters.ExStyle |= 0x00000080 | 0x08000000;
                return parameters;
            }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            using (var pen = new Pen(Color.FromArgb(196, 213, 216)))
                e.Graphics.DrawRectangle(pen, 0, 0, Width - 1, Height - 1);
        }

        protected override void Dispose(bool disposing)
        {
            base.Dispose(disposing);
            if (!disposing) return;
            headline.Dispose();
            content.Dispose();
            closeFont.Dispose();
        }
    }

    private readonly Form anchor;
    private readonly Action play;
    private readonly Stopwatch clock = Stopwatch.StartNew();
    private readonly Timer timer = new Timer { Interval = 150 };
    private readonly Queue<Notice> waiting = new Queue<Notice>();
    private readonly List<Popup> visible = new List<Popup>();
    private Rectangle area;
    private int dpi;
    private bool disposing;

    internal DesktopNotices(Form anchor, Action play)
    {
        this.anchor = anchor;
        this.play = play;
        timer.Tick += Tick;
    }

    internal void Push(string title, string text, bool sound)
    {
        if (visible.Count == 0)
        {
            area = Screen.FromControl(anchor).WorkingArea;
            dpi = anchor.DeviceDpi;
        }
        waiting.Enqueue(new Notice(title, text, sound));
        Pump();
        timer.Start();
    }

    private void Pump()
    {
        int below = 0;
        foreach (var item in visible)
        {
            item.Bounds = ToastPlacement.At(area, item.Size, below, dpi);
            below += item.Height + ToastPlacement.Gap(dpi);
        }
        while (waiting.Count > 0 && visible.Count < ToastPlacement.MaxVisible)
        {
            var next = waiting.Peek();
            var panel = new Popup(next, ToastPlacement.Width(area, dpi), dpi,
                    Math.Max(1, area.Height - ToastPlacement.Scale(32, dpi)));
            Rectangle bounds = ToastPlacement.At(area, panel.Size, below, dpi);
            if (!ToastPlacement.Fits(area, bounds, dpi) && visible.Count > 0)
            {
                panel.Dispose();
                break;
            }
            panel.Bounds = bounds;
            panel.FormClosed += (sender, args) =>
            {
                visible.Remove(panel);
                if (!disposing) Pump();
            };
            panel.Show();
            visible.Add(panel);
            waiting.Dequeue();
            panel.Expires = clock.ElapsedMilliseconds + ToastPlacement.Duration(next.Text);
            below += panel.Height + ToastPlacement.Gap(dpi);
            if (next.Sound) play();
        }
        if (waiting.Count == 0 && visible.Count == 0) timer.Stop();
    }

    private void Tick(object sender, EventArgs args)
    {
        long now = clock.ElapsedMilliseconds;
        foreach (var panel in visible.ToArray())
        {
            if (panel.Bounds.Contains(Cursor.Position) && panel.HoverExtension < 9000)
            {
                panel.HoverExtension += timer.Interval;
                panel.Expires += timer.Interval;
            }
            if (panel.Expires <= now) panel.Close();
        }
    }

    public void Dispose()
    {
        disposing = true;
        timer.Dispose();
        waiting.Clear();
        foreach (var panel in visible.ToArray()) panel.Close();
        visible.Clear();
    }
}
