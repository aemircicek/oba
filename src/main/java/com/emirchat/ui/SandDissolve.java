package com.emirchat.ui;

import javafx.animation.AnimationTimer;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

/**
 * A message that disappears turns to sand: the bubble is photographed, cut into grains, and a
 * wave runs across it from left to right, each grain taking on the colour of sand and drifting
 * away on the wind while it falls and fades. Pure decoration; with "Reduce motion" the message
 * simply goes.
 */
final class SandDissolve {
    /** How long the whole effect takes. */
    static final double SECONDS = 1.7;
    private static final Color SAND = Color.web("#d9a066");
    private static final Color SAND_DARK = Color.web("#b97b3e");

    private SandDissolve() {}

    /**
     * Turns {@code node} to sand over {@code layer} (a pane that covers it), then runs {@code after}.
     * The node is hidden at once; {@code after} usually removes or replaces it.
     */
    static void play(Node node, Pane layer, Runnable after) {
        if (!Motion.enabled() || node.getScene() == null || layer.getScene() == null) {
            after.run();
            return;
        }
        Bounds local = node.getBoundsInLocal();
        if (local.getWidth() < 2 || local.getHeight() < 2) {
            after.run();
            return;
        }
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        WritableImage picture = node.snapshot(params, null);
        Bounds at = layer.sceneToLocal(node.localToScene(local));
        Grains grains = Grains.from(picture, at.getMinX(), at.getMinY(), new java.util.Random(node.hashCode()));
        Canvas canvas = new Canvas(layer.getWidth(), layer.getHeight());
        canvas.setManaged(false);
        canvas.setMouseTransparent(true);
        layer.getChildren().add(canvas);
        node.setOpacity(0);
        GraphicsContext g = canvas.getGraphicsContext2D();
        new AnimationTimer() {
            private long start = -1;
            private long last;

            @Override public void handle(long now) {
                if (start < 0) {
                    start = now;
                    last = now;
                }
                double t = (now - start) / 1e9;
                double dt = Math.min(0.05, (now - last) / 1e9);
                last = now;
                g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
                grains.step(t, dt);
                grains.draw(g, t);
                if (t >= SECONDS) {
                    stop();
                    layer.getChildren().remove(canvas);
                    after.run();
                }
            }
        }.start();
    }

    /** The grains of one photographed message, as parallel arrays (thousands of them, every frame). */
    static final class Grains {
        final int n;
        final double[] x;
        final double[] y;
        final double[] vx;
        final double[] vy;
        final double[] release;  // when the wave reaches the grain (s)
        final double[] size;
        final Color[] color;

        private Grains(int n) {
            this.n = n;
            x = new double[n];
            y = new double[n];
            vx = new double[n];
            vy = new double[n];
            release = new double[n];
            size = new double[n];
            color = new Color[n];
        }

        /**
         * One grain per visible 2x2 pixel block (3x3 for big bubbles), released by a wave that
         * crosses the bubble in 0.7 s with a ragged front.
         */
        static Grains from(WritableImage picture, double originX, double originY, java.util.Random random) {
            int w = (int) picture.getWidth();
            int h = (int) picture.getHeight();
            int step = w * h > 120_000 ? 3 : 2;
            PixelReader pixels = picture.getPixelReader();
            java.util.List<int[]> found = new java.util.ArrayList<>();
            for (int py = 0; py < h; py += step) {
                for (int px = 0; px < w; px += step) {
                    int argb = pixels.getArgb(px, py);
                    if ((argb >>> 24) > 40) found.add(new int[] {px, py, argb});
                }
            }
            Grains g = new Grains(found.size());
            for (int i = 0; i < g.n; i++) {
                int[] p = found.get(i);
                g.x[i] = originX + p[0];
                g.y[i] = originY + p[1];
                g.size[i] = step * (0.8 + random.nextDouble() * 0.5);
                double front = (double) p[0] / Math.max(1, w);
                g.release[i] = front * 0.7 + random.nextDouble() * 0.18 + Math.sin(p[1] * 0.25) * 0.03;
                g.vx[i] = 30 + random.nextDouble() * 70;     // the wind blows right
                g.vy[i] = -25 - random.nextDouble() * 45;    // grains lift first, then fall
                g.color[i] = Color.rgb((p[2] >> 16) & 0xff, (p[2] >> 8) & 0xff, p[2] & 0xff);
            }
            return g;
        }

        void step(double t, double dt) {
            for (int i = 0; i < n; i++) {
                if (t < release[i]) continue;
                vx[i] += 90 * dt;                     // gusts pick up
                vy[i] += 160 * dt;                    // and gravity wins
                x[i] += vx[i] * dt;
                y[i] += vy[i] * dt;
            }
        }

        void draw(GraphicsContext g, double t) {
            for (int i = 0; i < n; i++) {
                double age = t - release[i];
                double alpha;
                Color c;
                if (age < 0) {
                    alpha = 1;
                    c = color[i];
                } else {
                    double life = Math.min(1, age / 0.85);
                    alpha = 1 - life;
                    // the grain turns to sand as it goes, light and dark grains mixed
                    c = color[i].interpolate(i % 3 == 0 ? SAND_DARK : SAND, Math.min(1, age * 4));
                }
                if (alpha <= 0.01) continue;
                g.setGlobalAlpha(alpha);
                g.setFill(c);
                double s = age < 0 ? size[i] : size[i] * (1 - Math.min(0.5, age * 0.6));
                g.fillRect(x[i], y[i], s, s);
            }
            g.setGlobalAlpha(1);
        }
    }
}
