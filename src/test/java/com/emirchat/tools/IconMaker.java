package com.emirchat.tools;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
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
 * Development tool: draws the Oba app icon (two tents around a campfire under a night sky, with
 * a crescent moon) and writes every size the app and the installers need.
 *
 * <pre>java -cp ... com.emirchat.tools.IconMaker &lt;repository root&gt;</pre>
 */
public final class IconMaker {
    static final Color NIGHT = new Color(0x1E1833);
    static final Color DUSK = new Color(0x5A2E3A);
    static final Color HORIZON = new Color(0xB4561A);
    static final Color GROUND = new Color(0x2A1A10);
    static final Color CANVAS = new Color(0xFFF1DC);
    static final Color CANVAS_SHADE = new Color(0xE3BE8E);
    static final Color DOOR = new Color(0x3B2416);
    static final Color FLAME = new Color(0xF2A43A);
    static final Color FLAME_IN = new Color(0xFFD36E);
    static final Color FLAME_CORE = new Color(0xFFF6D6);
    static final Color LOG = new Color(0x5A3519);

    private IconMaker() {}

    /**
     * The icon drawn on a 1024-unit canvas, scaled to {@code size} pixels. Below 48 pixels it is
     * reduced to one tent and a big flame (no stars, moon or second tent), so it still reads in a
     * taskbar or the tray.
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

        // the sky: night above, the warm glow of the horizon below
        RoundRectangle2D tile = new RoundRectangle2D.Double(32, 32, 960, 960, 220, 220);
        g.setPaint(new java.awt.LinearGradientPaint(0, 32, 0, 992, new float[] {0f, 0.55f, 1f},
                new Color[] {NIGHT, DUSK, HORIZON}));
        g.fill(tile);
        g.setClip(tile);

        if (detailed) {
            g.setColor(new Color(255, 246, 220, 220));
            double[][] stars = {{180, 170, 10}, {300, 120, 7}, {470, 200, 9}, {610, 130, 6}, {250, 290, 6}, {560, 290, 5}};
            for (double[] s : stars) g.fill(new Ellipse2D.Double(s[0] - s[2], s[1] - s[2], s[2] * 2, s[2] * 2));
            // a crescent: a disc with another disc cut out of it (the sky shows through)
            java.awt.geom.Area moon = new java.awt.geom.Area(new Ellipse2D.Double(730, 130, 160, 160));
            moon.subtract(new java.awt.geom.Area(new Ellipse2D.Double(778, 104, 160, 160)));
            g.setColor(new Color(0xF6ECD2));
            g.fill(moon);
        }

        // the fire's light on everything around it
        float fx = detailed ? 512 : 640;
        float fy = 800;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(fx, fy), detailed ? 470 : 520, new float[] {0f, 0.5f, 1f},
                new Color[] {new Color(255, 170, 70, 170), new Color(240, 120, 40, 60), new Color(240, 120, 40, 0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(tile);

        // the ground: a low dune
        GeneralPath ground = new GeneralPath();
        ground.moveTo(0, 820);
        ground.curveTo(260, 760, 700, 770, 1024, 830);
        ground.lineTo(1024, 1024);
        ground.lineTo(0, 1024);
        ground.closePath();
        g.setColor(GROUND);
        g.fill(ground);

        if (detailed) {
            tent(g, 310, 350, 250, 800, 1.0);    // the big tent, left
            tent(g, 735, 470, 175, 805, 0.9);    // a smaller one, right
            fire(g, 512, 860, 1.0);
        } else {
            tent(g, 400, 300, 330, 820, 1.0);
            fire(g, 735, 880, 1.25);
        }
        g.dispose();
        return img;
    }

    /** A tent: lit left face, shaded right face, a dark door, pegs and a little flag on top. */
    private static void tent(Graphics2D g, double apexX, double apexY, double halfWidth, double baseY, double light) {
        double left = apexX - halfWidth;
        double right = apexX + halfWidth;
        GeneralPath lit = new GeneralPath();
        lit.moveTo(apexX, apexY);
        lit.lineTo(left, baseY);
        lit.lineTo(apexX, baseY);
        lit.closePath();
        GeneralPath shade = new GeneralPath();
        shade.moveTo(apexX, apexY);
        shade.lineTo(apexX, baseY);
        shade.lineTo(right, baseY);
        shade.closePath();
        g.setPaint(new GradientPaint((float) left, (float) baseY, CANVAS, (float) apexX, (float) apexY, mix(CANVAS, CANVAS_SHADE, 0.25)));
        g.fill(lit);
        g.setColor(mix(CANVAS_SHADE, new Color(0x9C7650), 1 - light));
        g.fill(shade);
        // the door: a narrow dark triangle with one flap folded back
        double doorH = (baseY - apexY) * 0.55;
        double doorW = halfWidth * 0.42;
        GeneralPath door = new GeneralPath();
        door.moveTo(apexX, baseY - doorH);
        door.lineTo(apexX - doorW * 0.35, baseY);
        door.lineTo(apexX + doorW, baseY);
        door.closePath();
        g.setColor(DOOR);
        g.fill(door);
        GeneralPath flap = new GeneralPath();
        flap.moveTo(apexX, baseY - doorH);
        flap.lineTo(apexX + doorW, baseY);
        flap.lineTo(apexX + doorW * 1.35, baseY - doorH * 0.12);
        flap.closePath();
        g.setColor(mix(CANVAS, FLAME_IN, 0.35));
        g.fill(flap);
        // a little pennant at the top
        GeneralPath pole = new GeneralPath();
        pole.moveTo(apexX - 5, apexY + 8);
        pole.lineTo(apexX - 5, apexY - 70);
        pole.lineTo(apexX + 5, apexY - 70);
        pole.lineTo(apexX + 5, apexY + 8);
        pole.closePath();
        g.setColor(DOOR);
        g.fill(pole);
        GeneralPath pennant = new GeneralPath();
        pennant.moveTo(apexX + 5, apexY - 70);
        pennant.lineTo(apexX + 70, apexY - 52);
        pennant.lineTo(apexX + 5, apexY - 34);
        pennant.closePath();
        g.setColor(FLAME);
        g.fill(pennant);
    }

    /** Crossed logs and a three-layer flame (orange, yellow, white-hot core), and a few sparks. */
    private static void fire(Graphics2D g, double cx, double baseY, double scale) {
        g.setColor(LOG);
        for (int side = -1; side <= 1; side += 2) {
            Graphics2D l = (Graphics2D) g.create();
            l.rotate(Math.toRadians(side * 18), cx, baseY);
            l.fill(new RoundRectangle2D.Double(cx - 150 * scale, baseY - 18 * scale, 300 * scale, 36 * scale, 30 * scale, 30 * scale));
            l.dispose();
        }
        flame(g, cx, baseY - 10 * scale, 118 * scale, 330 * scale, FLAME);
        flame(g, cx + 6 * scale, baseY - 14 * scale, 78 * scale, 230 * scale, FLAME_IN);
        flame(g, cx + 8 * scale, baseY - 18 * scale, 40 * scale, 130 * scale, FLAME_CORE);
        g.setColor(new Color(255, 210, 120, 230));
        double[][] sparks = {{-60, -390, 11}, {55, -440, 9}, {10, -500, 7}};
        for (double[] s : sparks) {
            double r = s[2] * scale;
            g.fill(new Ellipse2D.Double(cx + s[0] * scale - r, baseY + s[1] * scale - r, r * 2, r * 2));
        }
    }

    /** A teardrop flame standing on (cx, base), with a slight lean like a breeze. */
    private static void flame(Graphics2D g, double cx, double base, double halfWidth, double height, Color c) {
        double top = base - height;
        GeneralPath p = new GeneralPath();
        p.moveTo(cx + halfWidth * 0.15, top);
        p.curveTo(cx + halfWidth * 1.15, base - height * 0.45, cx + halfWidth * 1.0, base, cx, base);
        p.curveTo(cx - halfWidth * 1.05, base, cx - halfWidth * 1.1, base - height * 0.5, cx - halfWidth * 0.25, base - height * 0.72);
        p.curveTo(cx - halfWidth * 0.1, base - height * 0.55, cx + halfWidth * 0.05, base - height * 0.62, cx + halfWidth * 0.15, top);
        p.closePath();
        g.setColor(c);
        g.fill(p);
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
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
        Files.write(Files.createDirectories(packaging.resolve("windows")).resolve("oba.ico"), ico(16, 24, 32, 48, 64, 128, 256));
        Files.write(Files.createDirectories(packaging.resolve("linux")).resolve("oba.png"), png(draw(512)));
        // macOS: an .iconset folder; CI turns it into oba.icns with iconutil
        Path iconset = Files.createDirectories(packaging.resolve("macos/oba.iconset"));
        for (int s : new int[] {16, 32, 128, 256, 512}) {
            Files.write(iconset.resolve("icon_" + s + "x" + s + ".png"), png(draw(s)));
            Files.write(iconset.resolve("icon_" + s + "x" + s + "@2x.png"), png(draw(s * 2)));
        }
        Files.write(root.resolve("docs/oba-icon.png"), png(draw(1024)));
        System.out.println("icons written under " + root.toAbsolutePath());
    }
}
