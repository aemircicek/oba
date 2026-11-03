package com.emirchat.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Development tool: draws the Scorpion app icon (a padlock whose shackle is a scorpion's tail)
 * and writes every size the app and the installers need.
 *
 * <pre>java -cp ... com.emirchat.tools.IconMaker &lt;repository root&gt;</pre>
 */
public final class IconMaker {
    /** Desert-night amber, the app's accent (see app.css). */
    static final Color DARK = new Color(0x8A450C);
    static final Color LIGHT = new Color(0xF5AE45);
    static final Color MID = new Color(0xC7731C);
    static final Color CREAM = new Color(0xFFF7EC);

    private IconMaker() {}

    /**
     * The icon drawn on a 1024-unit canvas, scaled to {@code size} pixels. Below 48 pixels the
     * tail's segment cuts and the keyhole's shading disappear and the tail gets thicker, so the
     * silhouette stays readable in a taskbar or the tray.
     */
    static BufferedImage draw(int size) {
        return draw(size, size >= 48);
    }

    static BufferedImage draw(int size, boolean detailed) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.scale(size / 1024.0, size / 1024.0);

        // background: rounded square with a diagonal green gradient
        g.setPaint(new GradientPaint(0, 0, LIGHT, 1024, 1024, DARK));
        g.fill(new RoundRectangle2D.Double(32, 32, 960, 960, 220, 220));

        Color white = CREAM;
        Color gap = MID;
        g.setColor(white);
        // lock body (the keyhole is cut out below)
        g.fill(new RoundRectangle2D.Double(262, 560, 500, 380, 90, 90));

        // tail: a tapering curve rising from the lock and arching to the left (cubic Bezier)
        double[] p0 = {640, 600};
        double[] p1 = {790, 200};
        double[] p2 = {280, 60};
        double[] p3 = {300, 330};
        int n = 120;
        double[][] left = new double[n + 1][];
        double[][] right = new double[n + 1][];
        double[][] centre = new double[n + 1][];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double[] p = bezier(p0, p1, p2, p3, t);
            double[] d = derivative(p0, p1, p2, p3, t);
            double len = Math.hypot(d[0], d[1]);
            double nx = -d[1] / len;
            double ny = d[0] / len;
            double w = (detailed ? 128 - 62 * t : 150 - 50 * t) / 2; // thick at the base, thin at the tip
            centre[i] = p;
            left[i] = new double[] {p[0] + nx * w, p[1] + ny * w};
            right[i] = new double[] {p[0] - nx * w, p[1] - ny * w};
        }
        GeneralPath tail = new GeneralPath();
        tail.moveTo(left[0][0], left[0][1]);
        for (int i = 1; i <= n; i++) tail.lineTo(left[i][0], left[i][1]);
        for (int i = n; i >= 0; i--) tail.lineTo(right[i][0], right[i][1]);
        tail.closePath();
        g.fill(tail);
        // segment joints: thin cuts across the tail
        g.setColor(gap);
        g.setStroke(new BasicStroke(16, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        for (double t : detailed ? new double[] {0.22, 0.42, 0.61, 0.79} : new double[0]) {
            int i = (int) Math.round(t * n);
            g.draw(new java.awt.geom.Line2D.Double(left[i][0], left[i][1], right[i][0], right[i][1]));
        }
        // venom bulb just past the tip (separated by a thin gap), and a sharp stinger hooking
        // down and towards the lock
        double bx = centre[n][0] + 2;
        double by = centre[n][1] + 78;
        g.setColor(gap);
        g.fill(new Ellipse2D.Double(bx - 68, by - 70, 136, 148));
        g.setColor(white);
        g.fill(new Ellipse2D.Double(bx - 58, by - 60, 116, 128));
        GeneralPath sting = new GeneralPath();
        sting.moveTo(bx + 20, by + 52);
        sting.curveTo(bx + 45, by + 105, bx + 100, by + 128, bx + 168, by + 112);
        sting.curveTo(bx + 112, by + 98, bx + 78, by + 70, bx + 56, by + 22);
        sting.closePath();
        g.fill(sting);
        if (detailed) g.setPaint(new GradientPaint(0, 560, MID, 0, 940, DARK));
        else g.setColor(DARK);
        g.fill(new Ellipse2D.Double(512 - 56, 650, 112, 112));
        GeneralPath slot = new GeneralPath();
        slot.moveTo(512 - 32, 730);
        slot.lineTo(512 + 32, 730);
        slot.lineTo(512 + 48, 860);
        slot.lineTo(512 - 48, 860);
        slot.closePath();
        g.fill(slot);        g.dispose();
        return img;
    }

    private static double[] bezier(double[] a, double[] b, double[] c, double[] d, double t) {
        double u = 1 - t;
        return new double[] {
                u * u * u * a[0] + 3 * u * u * t * b[0] + 3 * u * t * t * c[0] + t * t * t * d[0],
                u * u * u * a[1] + 3 * u * u * t * b[1] + 3 * u * t * t * c[1] + t * t * t * d[1]};
    }

    private static double[] derivative(double[] a, double[] b, double[] c, double[] d, double t) {
        double u = 1 - t;
        return new double[] {
                3 * u * u * (b[0] - a[0]) + 6 * u * t * (c[0] - b[0]) + 3 * t * t * (d[0] - c[0]),
                3 * u * u * (b[1] - a[1]) + 6 * u * t * (c[1] - b[1]) + 3 * t * t * (d[1] - c[1])};
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** A Windows .ico holding PNG images (supported since Windows Vista). */
    static byte[] ico(int... sizes) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int s : sizes) images.add(png(draw(s)));
        int offset = 6 + 16 * sizes.length;
        ByteBuffer b = ByteBuffer.allocate(offset + images.stream().mapToInt(i -> i.length).sum()).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            b.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            b.putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        for (byte[] image : images) b.put(image);
        return b.array();
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path ui = Files.createDirectories(root.resolve("src/main/resources/com/emirchat/ui/icons"));
        for (int s : new int[] {16, 24, 32, 48, 64, 128, 256, 512}) Files.write(ui.resolve("icon-" + s + ".png"), png(draw(s)));
        Path packaging = root.resolve("packaging");
        Files.write(Files.createDirectories(packaging.resolve("windows")).resolve("scorpion.ico"), ico(16, 24, 32, 48, 64, 128, 256));
        Files.write(Files.createDirectories(packaging.resolve("linux")).resolve("scorpion.png"), png(draw(512)));
        // macOS: an .iconset folder; CI turns it into scorpion.icns with iconutil
        Path iconset = Files.createDirectories(packaging.resolve("macos/scorpion.iconset"));
        for (int s : new int[] {16, 32, 128, 256, 512}) {
            Files.write(iconset.resolve("icon_" + s + "x" + s + ".png"), png(draw(s)));
            Files.write(iconset.resolve("icon_" + s + "x" + s + "@2x.png"), png(draw(s * 2)));
        }
        Files.write(root.resolve("docs/scorpion-icon.png"), png(draw(1024)));
        System.out.println("icons written under " + root.toAbsolutePath());
    }
}
