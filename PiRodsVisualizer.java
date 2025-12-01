/**
 * π Rods Visualizer
 * 
 * Swing application that shows π in action with two rotating rods.
 * Rod B can be locked to rotate at π times Rod A, creating interesting non-repeating patterns.
 * Includes trails, grids, phase plots, and image/frame exporting.
 * 
 * Made simple, intuitive, and fun to play with.
 */

import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.text.DecimalFormat;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * Single-file Swing application that visualizes π using two rotating rods.
 * This file is a corrected version where VisualPanel exposes safe getters (isRunning, getFadeAlpha)
 * so the ControlPanel does not access private fields directly.
 */
public class PiRodsVisualizer {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("π Rods Visualizer");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setSize(1000, 700);
            f.setLayout(new BorderLayout());

            VisualPanel visual = new VisualPanel();
            ControlPanel controls = new ControlPanel(visual);

            f.add(visual, BorderLayout.CENTER);
            f.add(controls, BorderLayout.EAST);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}

/* ---------------------------------------------------------------------
   VisualPanel: custom JPanel responsible for rendering and animation.
   --------------------------------------------------------------------- */
class VisualPanel extends JPanel {
    // Simulation parameters (with sane defaults)
    volatile double L1 = 200, L2 = 150;
    volatile double omega1 = 1.0, omega2 = Math.PI; // rad/s; default omega2 = pi * omega1 (lock feature will enforce)
    volatile boolean lockRatioToPi = true;

    // Rendering / animation
    private volatile BufferedImage trailImg;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Timer timer;
    private long lastNanoTime = System.nanoTime();

    // Continuous angles (unwrapped totals) for robust counting
    private double thetaTotal1 = 0.0;
    private double thetaTotal2 = 0.0;

    // UI toggles
    volatile boolean showTrail = true;
    volatile boolean showGrid = true;
    volatile boolean showInfo = true;
    volatile boolean showPhasePlot = true;

    // Estimator: measure over multiple full revolutions of rod A (use unwrapped angles)
    private long measuredRevs1 = 0; // integer revolutions counted
    private double lastMeasuredTheta1 = 0.0;
    private double lastMeasuredTheta2 = 0.0;
    private double empiricalRatio = Double.NaN;
    private double empiricalError = Double.NaN;
    private int measureWindowRevs = 5; // estimate after this many full revolutions of rod A

    // Phase difference circular buffer for plot (θ2 - π*θ1)
    private static final int PHASE_HISTORY = 800;
    private final double[] phaseHistory = new double[PHASE_HISTORY];
    private int phaseIndex = 0;

    // Trail fade parameters
    private float fadeAlpha = 0.05f; // how fast trail fades (0..1). Lower = longer persistence.

    // Drawing options
    private final Stroke rodStroke = new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private final Stroke thinStroke = new BasicStroke(1.5f);
    private final DecimalFormat df12 = new DecimalFormat("0.000000000000");

    // Interaction / UX
    private Point origin = new Point(500, 350); // default, will recenter on resize
    private final Object trailLock = new Object();

    // For drawing tip marker
    private final int tipSize = 6;

    public VisualPanel() {
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        setPreferredSize(new Dimension(800, 700));

        // initialize trail
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                createTrailImage();
                recenterOrigin();
            }

            @Override
            public void componentShown(ComponentEvent e) {
                createTrailImage();
                recenterOrigin();
            }
        });
        createTrailImage();
        recenterOrigin();

        // Timer for animation: target ~60 FPS (16 ms)
        timer = new Timer(16, e -> animateFrame());
        timer.setCoalesce(true);
        timer.start();

        // mouse control: click to toggle pause/play
        addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                // double-click toggles pause; single click toggles info overlay
                if (e.getClickCount() == 2) toggleRunning();
                else if (SwingUtilities.isRightMouseButton(e)) showInfo = !showInfo;
            }
        });
    }

    private void createTrailImage() {
        synchronized (trailLock) {
            int w = Math.max(2, getWidth());
            int h = Math.max(2, getHeight());
            BufferedImage newImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = newImg.createGraphics();
            // start with transparent black
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, w, h);
            g.dispose();
            trailImg = newImg;
        }
    }

    private void recenterOrigin() {
        origin = new Point(getWidth() / 2, getHeight() / 2);
    }

    // Toggle run/pause
    public void toggleRunning() {
        boolean newState = !running.get();
        running.set(newState);
    }

    // Start/Stop control
    public void setRunning(boolean r) {
        running.set(r);
    }

    // Public getter for run state (fixes access issue)
    public boolean isRunning() {
        return running.get();
    }

    // Reset state: clear trail and angles
    public void resetSimulation() {
        synchronized (trailLock) {
            if (trailImg != null) {
                Graphics2D g = trailImg.createGraphics();
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(0, 0, trailImg.getWidth(), trailImg.getHeight());
                g.dispose();
            }
        }
        thetaTotal1 = 0.0;
        thetaTotal2 = 0.0;
        lastMeasuredTheta1 = 0.0;
        lastMeasuredTheta2 = 0.0;
        measuredRevs1 = 0;
        empiricalRatio = Double.NaN;
        empiricalError = Double.NaN;
        for (int i = 0; i < PHASE_HISTORY; i++) phaseHistory[i] = 0.0;
        phaseIndex = 0;
        repaint();
    }

    // Save current frame to PNG
    public void saveCurrentPNG(File f) throws IOException {
        BufferedImage snap = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = snap.createGraphics();
        paintComponentTo(g, true);
        g.dispose();
        ImageIO.write(snap, "png", f);
    }

    // Export N frames as a time-lapse PNG series (uses SwingWorker to avoid blocking the EDT)
    public void exportFrames(File folder, int frames, double secondsBetweenFrames) {
        if (!folder.exists()) folder.mkdirs();
        final double stepSeconds = secondsBetweenFrames;
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                setRunning(false);
                long saved = 0;
                for (int i = 0; i < frames; i++) {
                    // advance simulation by stepSeconds (simulate in small increments to keep integrator stable)
                    double remaining = stepSeconds;
                    while (remaining > 0) {
                        double dt = Math.min(0.02, remaining); // 20 ms step for simulation
                        stepSimulation(dt);
                        remaining -= dt;
                    }
                    // render snapshot and save
                    BufferedImage snap = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = snap.createGraphics();
                    paintComponentTo(g, true);
                    g.dispose();
                    File out = new File(folder, String.format("frame_%05d.png", i));
                    ImageIO.write(snap, "png", out);
                    saved++;
                    setProgress((int) (100.0 * saved / frames));
                }
                return null;
            }

            @Override
            protected void done() {
                setRunning(true);
                JOptionPane.showMessageDialog(VisualPanel.this, "Export finished to: " + folder.getAbsolutePath());
            }
        };
        worker.execute();
    }

    // Step simulation by dt seconds (used by both animation loop and export)
    private void stepSimulation(double dt) {
        // if lock-to-pi is on, ensure omega2 tracks omega1
        if (lockRatioToPi) omega2 = omega1 * Math.PI;

        // integrate angles (unwrapped totals)
        thetaTotal1 += omega1 * dt;
        thetaTotal2 += omega2 * dt;

        // record phase difference history (θ2 - π*θ1), wrapped into [-pi, pi] for display stability
        double rawPhase = thetaTotal2 - Math.PI * thetaTotal1;
        double p = wrapToPi(rawPhase);
        phaseHistory[phaseIndex] = p;
        phaseIndex = (phaseIndex + 1) % PHASE_HISTORY;

        // Estimator: when rod A completes revolutions, check counts
        double revsNow = Math.floor(thetaTotal1 / (2 * Math.PI));
        double revsLast = Math.floor(lastMeasuredTheta1 / (2 * Math.PI));
        long diffRevs = (long) (revsNow - revsLast);
        if (diffRevs >= measureWindowRevs) {
            // measure across the window of real (unwrapped) angles for stability:
            double delta1 = thetaTotal1 - lastMeasuredTheta1;
            double delta2 = thetaTotal2 - lastMeasuredTheta2;
            if (Math.abs(delta1) > 1e-9) {
                empiricalRatio = delta2 / delta1;
                empiricalError = Math.abs(empiricalRatio - Math.PI);
            }
            // update last measured
            lastMeasuredTheta1 = thetaTotal1;
            lastMeasuredTheta2 = thetaTotal2;
            measuredRevs1 += diffRevs;
        }
    }

    // animation tick
    private void animateFrame() {
        long now = System.nanoTime();
        double dt = (now - lastNanoTime) / 1e9;
        if (dt <= 0) dt = 1.0 / 60.0;
        lastNanoTime = now;

        if (running.get()) {
            stepSimulation(dt);

            // draw tip to trail image (with lock)
            if (showTrail) {
                Point tip = computeTipPosition();
                synchronized (trailLock) {
                    if (trailImg == null || trailImg.getWidth() != getWidth() || trailImg.getHeight() != getHeight()) {
                        createTrailImage();
                    }
                    Graphics2D g = trailImg.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    // fade trail a bit (draw translucent rectangle)
                    Composite old = g.getComposite();
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fadeAlpha));
                    g.setColor(new Color(0, 0, 0, 1f)); // paint tiny alpha to fade
                    g.fillRect(0, 0, trailImg.getWidth(), trailImg.getHeight());
                    g.setComposite(old);
                    // draw the new tip point (bright color)
                    g.setColor(new Color(255, 200, 40, 220));
                    g.fillOval(tip.x - 2, tip.y - 2, 4, 4);
                    g.dispose();
                }
            }
        }

        // repaint to show new frame
        repaint();
    }

    // compute positions
    private Point computeFirstJoint() {
        int x = origin.x + (int) Math.round(L1 * Math.cos(thetaTotal1));
        int y = origin.y + (int) Math.round(L1 * Math.sin(thetaTotal1));
        return new Point(x, y);
    }

    private Point computeTipPosition() {
        Point j1 = computeFirstJoint();
        double absThetaB = thetaTotal1 + thetaTotal2; // rod B rotates relative to A; absolute = theta1 + theta2(rel)
        int x = j1.x + (int) Math.round(L2 * Math.cos(absThetaB));
        int y = j1.y + (int) Math.round(L2 * Math.sin(absThetaB));
        return new Point(x, y);
    }

    // utility to bring angle to [-pi, pi]
    private static double wrapToPi(double ang) {
        double a = ang % (2 * Math.PI);
        if (a <= -Math.PI) a += 2 * Math.PI;
        else if (a > Math.PI) a -= 2 * Math.PI;
        return a;
    }

    // render everything
    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        paintComponentTo(g, false);
        g.dispose();
    }

    // draw into Graphics2D; if offscreen=true, draw for saving snapshots (some overlays might be skipped)
    private void paintComponentTo(Graphics2D g, boolean offscreen) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // background
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());

        // draw faded trail image
        synchronized (trailLock) {
            if (trailImg != null && showTrail) {
                g.drawImage(trailImg, 0, 0, null);
            }
        }

        // grid / axis
        if (showGrid) {
            g.setStroke(thinStroke);
            g.setColor(new Color(100, 100, 100, 40));
            int step = 50;
            for (int x = origin.x % step; x < getWidth(); x += step) g.drawLine(x, 0, x, getHeight());
            for (int y = origin.y % step; y < getHeight(); y += step) g.drawLine(0, y, getWidth(), y);
            // axis
            g.setColor(new Color(180, 180, 180, 80));
            g.setStroke(new BasicStroke(2.0f));
            g.drawLine(0, origin.y, getWidth(), origin.y);
            g.drawLine(origin.x, 0, origin.x, getHeight());
        }

        // compute joint positions
        Point j1 = computeFirstJoint();
        Point tip = computeTipPosition();

        // rods
        g.setStroke(rodStroke);
        // rod A
        g.setColor(new Color(120, 200, 255));
        g.drawLine(origin.x, origin.y, j1.x, j1.y);
        // rod B
        g.setColor(new Color(255, 160, 80));
        g.drawLine(j1.x, j1.y, tip.x, tip.y);

        // joints / origin
        g.setColor(new Color(200, 200, 255));
        g.fillOval(origin.x - 6, origin.y - 6, 12, 12);
        g.setColor(new Color(220, 160, 120));
        g.fillOval(j1.x - 5, j1.y - 5, 10, 10);

        // tip marker
        g.setColor(new Color(255, 240, 210));
        g.fillOval(tip.x - tipSize, tip.y - tipSize, tipSize * 2, tipSize * 2);
        g.setColor(new Color(150, 80, 40));
        g.drawOval(tip.x - tipSize, tip.y - tipSize, tipSize * 2, tipSize * 2);

        // info overlay
        if (showInfo) {
            drawInfoOverlay(g);
        }

        // small help text overlay
        if (!offscreen) drawHelpOverlay(g);

        // phase plot
        if (showPhasePlot) {
            drawPhasePlot(g);
        }
    }

    private void drawInfoOverlay(Graphics2D g) {
        int pad = 8;
        int w = 360;
        int h = 200;
        int x = 12;
        int y = 12;

        // background box
        Composite oldComp = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.65f));
        g.setColor(new Color(8, 8, 12));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setComposite(oldComp);

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        int lx = x + pad;
        int ly = y + pad + 14;

        // coordinates and angles
        Point tip = computeTipPosition();
        g.drawString(String.format("Tip (x,y): %d, %d", tip.x - origin.x, origin.y - tip.y), lx, ly);
        ly += 18;
        g.drawString(String.format("L1: %.0f px   L2: %.0f px", L1, L2), lx, ly);
        ly += 18;
        g.drawString(String.format("θ1 (rad): %10.6f", wrapTo2PI(thetaTotal1)), lx, ly);
        ly += 18;
        g.drawString(String.format("θ2 (rad, rel): %10.6f", wrapTo2PI(thetaTotal2)), lx, ly);
        ly += 18;

        // omegas
        g.drawString(String.format("ω1: %7.6f rad/s  ω2: %7.6f rad/s", omega1, omega2), lx, ly);
        ly += 18;

        // ratio display with many decimals and highlight if equal to Math.PI
        double ratio = (Math.abs(omega1) < 1e-12) ? Double.NaN : omega2 / omega1;
        String ratioStr = (Double.isNaN(ratio) || Double.isInfinite(ratio)) ? "N/A" : df12.format(ratio);
        g.drawString("r = ω2 / ω1 = " + ratioStr, lx, ly);
        ly += 18;

        // highlight when ratio equals Math.PI within tiny epsilon
        if (!Double.isNaN(ratio) && Math.abs(ratio - Math.PI) < 1e-12) {
            g.setColor(new Color(200, 240, 200));
            g.fillRoundRect(lx - 4, ly - 16, 110, 18, 6, 6);
            g.setColor(Color.BLACK);
            g.drawString("r == Math.PI (exact)", lx, ly);
            g.setColor(Color.WHITE);
        } else {
            // show Math.PI for reference
            g.drawString("Math.PI = " + df12.format(Math.PI), lx, ly);
            ly += 18;
        }

        ly += 4;
        // empirical estimator
        g.drawString("Estimator: (Δθ2 / Δθ1) measured across " + measureWindowRevs + " revs of rod A", lx, ly);
        ly += 16;
        if (!Double.isNaN(empiricalRatio)) {
            g.drawString("approx = " + df12.format(empiricalRatio), lx, ly);
            ly += 18;
            g.drawString("|approx - π| = " + formatScientific(empiricalError), lx, ly);
        } else {
            g.drawString("approx = (waiting for " + measureWindowRevs + " revs of rod A)", lx, ly);
        }
    }

    // small help text
    private void drawHelpOverlay(Graphics2D g) {
        String[] help = {
            "π Rods Visualizer — try these experiments:",
            "  • Lock ratio to π and vary ω1: tip fills non-repeating dense pattern.",
            "  • Set ω2/ω1 = 22/7 to see near-periodic repeating pattern.",
            "  • Toggle Trail to compare persistence.",
            "  • Use 'Export Frames' to produce a time-lapse PNG series.",
            "Double-click to Start/Pause. Right-click to toggle this text."
        };
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int x = 12, y = getHeight() - help.length * 18 - 12;
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        g.setColor(new Color(2, 2, 6));
        g.fillRoundRect(x - 6, y - 6, 520, help.length * 18 + 12, 8, 8);
        g.setComposite(old);

        g.setColor(Color.LIGHT_GRAY);
        int ly = y + 14;
        for (String s : help) {
            g.drawString(s, x, ly);
            ly += 18;
        }
    }

    // phase plot at bottom-right
    private void drawPhasePlot(Graphics2D g) {
        int pw = 360, ph = 120;
        int px = getWidth() - pw - 12, py = getHeight() - ph - 12;
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
        g.setColor(new Color(10, 10, 16));
        g.fillRoundRect(px, py, pw, ph, 8, 8);
        g.setComposite(old);

        // axes
        g.setColor(Color.GRAY);
        g.drawRect(px + 8, py + 8, pw - 16, ph - 16);
        // plot
        int plotW = pw - 18, plotH = ph - 26;
        int baseX = px + 10, baseY = py + 10;
        g.setClip(baseX, baseY, plotW, plotH);
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(new Color(180, 220, 255));
        // map phase history ([-pi, pi]) to vertical
        for (int i = 0; i < PHASE_HISTORY - 1; i++) {
            int idx1 = (phaseIndex + i) % PHASE_HISTORY;
            int idx2 = (phaseIndex + i + 1) % PHASE_HISTORY;
            double v1 = phaseHistory[idx1]; // [-pi, pi]
            double v2 = phaseHistory[idx2];
            int sx = baseX + (int) Math.round((double) i / (PHASE_HISTORY - 1) * (plotW - 1));
            int sx2 = baseX + (int) Math.round((double) (i + 1) / (PHASE_HISTORY - 1) * (plotW - 1));
            int sy = baseY + (int) Math.round((1 - (v1 + Math.PI) / (2 * Math.PI)) * (plotH - 1));
            int sy2 = baseY + (int) Math.round((1 - (v2 + Math.PI) / (2 * Math.PI)) * (plotH - 1));
            g.drawLine(sx, sy, sx2, sy2);
        }
        g.setClip(null);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("Phase: θ2 - π*θ1 (wrapped)", px + 12, py + ph - 12);
        g.setColor(Color.GRAY);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        g.drawString("-π", px + pw - 28, py + ph - 5);
        g.drawString(" +π", px + pw - 28, py + 14);
    }

    private static double wrapTo2PI(double a) {
        double res = a % (2 * Math.PI);
        if (res < 0) res += 2 * Math.PI;
        return res;
    }

    private static String formatScientific(double v) {
        if (Double.isNaN(v)) return "N/A";
        if (v == 0) return "0";
        return String.format("%.3e", v);
    }

    // Public setters for control panel
    public void setL1(double v) { L1 = clamp(v, 10, Math.max(50, getWidth() - 20)); repaint(); }
    public void setL2(double v) { L2 = clamp(v, 10, Math.max(50, getHeight() - 20)); repaint(); }
    public void setOmega1(double w) { omega1 = w; if (lockRatioToPi) omega2 = omega1 * Math.PI; repaint(); }
    public void setOmega2(double w) { if (!lockRatioToPi) omega2 = w; else omega2 = omega1 * Math.PI; repaint(); }
    public void setLockRatio(boolean lock) { lockRatioToPi = lock; if (lock) omega2 = omega1 * Math.PI; repaint(); }
    public void setShowTrail(boolean v) { showTrail = v; repaint(); }
    public void setShowGrid(boolean v) { showGrid = v; repaint(); }
    public void setMeasureWindowRevs(int r) { measureWindowRevs = Math.max(1, r); }
    public void setFadeAlpha(float a) { fadeAlpha = clamp(a, 0.0f, 0.5f); }
    public void presetRatio(double r) {
        if (lockRatioToPi) { /* ignore preset */ }
        else {
            omega2 = omega1 * r;
        }
    }

    // Public getter for fadeAlpha (fixes access issue)
    public float getFadeAlpha() {
        return fadeAlpha;
    }

    // Helper clamp
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}

/* ---------------------------------------------------------------------
   ControlPanel: UI on the right with sliders, spinners, buttons.
   --------------------------------------------------------------------- */
class ControlPanel extends JPanel {
    private final VisualPanel visual;
    private final JSlider l1Slider, l2Slider;
    private final JSpinner omega1Spinner, omega2Spinner;
    private final JCheckBox lockCheck, trailCheck, gridCheck;
    private final JButton startPauseBtn, resetBtn, saveBtn, exportBtn;
    private final JButton presetPiBtn, preset22_7Btn, prevPresetE, randomIrrBtn;

    public ControlPanel(VisualPanel v) {
        this.visual = v;
        setPreferredSize(new Dimension(300, 700));
        setLayout(new BorderLayout());
        setBackground(new Color(32, 34, 37));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Title
        JLabel title = new JLabel("<html><b><span style='font-size:14pt; color:#F4F4F4'>π Rods Visualizer</span></b></html>");
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(title);
        top.add(Box.createVerticalStrut(8));

        // L1 slider
        l1Slider = new JSlider(10, 600, (int) v.L1);
        l1Slider.setMajorTickSpacing(100);
        l1Slider.setPaintTicks(true);
        l1Slider.setOpaque(false);
        l1Slider.addChangeListener(e -> {
            visual.setL1(l1Slider.getValue());
        });
        top.add(makeLabeledComponent("L1 (px)", l1Slider));

        // L2 slider
        l2Slider = new JSlider(10, 600, (int) v.L2);
        l2Slider.setMajorTickSpacing(100);
        l2Slider.setPaintTicks(true);
        l2Slider.setOpaque(false);
        l2Slider.addChangeListener(e -> {
            visual.setL2(l2Slider.getValue());
        });
        top.add(makeLabeledComponent("L2 (px)", l2Slider));

        top.add(Box.createVerticalStrut(6));

        // Omega spinners
        omega1Spinner = new JSpinner(new SpinnerNumberModel(v.omega1, -20.0, 20.0, 0.01));
        omega2Spinner = new JSpinner(new SpinnerNumberModel(v.omega2, -80.0, 80.0, 0.01));
        JComponent o1Comp = makeLabeledComponent("ω1 (rad/s)", omega1Spinner);
        JComponent o2Comp = makeLabeledComponent("ω2 (rad/s)", omega2Spinner);
        top.add(o1Comp);
        top.add(o2Comp);

        ((JSpinner.DefaultEditor) omega1Spinner.getEditor()).getTextField().setColumns(6);
        ((JSpinner.DefaultEditor) omega2Spinner.getEditor()).getTextField().setColumns(6);
        omega1Spinner.addChangeListener(e -> {
            double w1 = ((Number) omega1Spinner.getValue()).doubleValue();
            visual.setOmega1(w1);
            // If lock is on, update omega2 spinner display too
            JCheckBox lockCheck = new JCheckBox("Lock ratio to π");

            if (lockCheck != null && lockCheck.isSelected()) {
                omega2Spinner.setValue(visual.omega2);
            }
        });
        omega2Spinner.addChangeListener(e -> {
            double w2 = ((Number) omega2Spinner.getValue()).doubleValue();
            visual.setOmega2(w2);
        });

        top.add(Box.createVerticalStrut(6));

        // Lock ratio
        lockCheck = new JCheckBox("Lock ratio to π (ω2 = ω1 * π)", true);
        lockCheck.setOpaque(false);
        lockCheck.setForeground(Color.WHITE);
        lockCheck.setSelected(true);
        lockCheck.addActionListener(e -> {
            boolean s = lockCheck.isSelected();
            visual.setLockRatio(s);
            if (s) omega2Spinner.setValue(visual.omega2);
        });
        top.add(lockCheck);

        // Trail and grid toggles
        trailCheck = new JCheckBox("Show Trail", true);
        trailCheck.setOpaque(false); trailCheck.setForeground(Color.WHITE);
        trailCheck.addActionListener(e -> visual.setShowTrail(trailCheck.isSelected()));
        top.add(trailCheck);
        gridCheck = new JCheckBox("Show Grid / Axis", true);
        gridCheck.setOpaque(false); gridCheck.setForeground(Color.WHITE);
        gridCheck.addActionListener(e -> visual.setShowGrid(gridCheck.isSelected()));
        top.add(gridCheck);

        top.add(Box.createVerticalStrut(8));

        // Start/Pause/Reset/Save
        JPanel btnRow = new JPanel(new GridLayout(2, 2, 6, 6));
        btnRow.setOpaque(false);
        startPauseBtn = new JButton("Pause");
        startPauseBtn.addActionListener(e -> {
            boolean running = !visual.isRunning();
            visual.setRunning(running);
            startPauseBtn.setText(running ? "Pause" : "Start");
        });
        resetBtn = new JButton("Reset");
        resetBtn.addActionListener(e -> visual.resetSimulation());

        saveBtn = new JButton("Save Image (PNG)");
        saveBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save PNG");
            chooser.setSelectedFile(new File("pi_rods.png"));
            int res = chooser.showSaveDialog(ControlPanel.this);
            if (res == JFileChooser.APPROVE_OPTION) {
                try {
                    visual.saveCurrentPNG(chooser.getSelectedFile());
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(ControlPanel.this, "Error saving PNG: " + ex.getMessage());
                }
            }
        });

        exportBtn = new JButton("Export Frames...");
        exportBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select folder to save frames (choose any file inside desired folder then click Save)");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setApproveButtonText("Select");
            int res = chooser.showOpenDialog(ControlPanel.this);
            if (res == JFileChooser.APPROVE_OPTION) {
                File folder = chooser.getSelectedFile();
                String framesStr = JOptionPane.showInputDialog(ControlPanel.this, "Number of frames to export (e.g., 300):", "300");
                String stepStr = JOptionPane.showInputDialog(ControlPanel.this, "Seconds between frames (e.g., 0.1):", "0.05");
                try {
                    int frames = Integer.parseInt(framesStr);
                    double step = Double.parseDouble(stepStr);
                    visual.exportFrames(folder, frames, step);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(ControlPanel.this, "Invalid parameters: " + ex.getMessage());
                }
            }
        });

        btnRow.add(startPauseBtn);
        btnRow.add(resetBtn);
        btnRow.add(saveBtn);
        btnRow.add(exportBtn);
        top.add(btnRow);

        top.add(Box.createVerticalStrut(8));

        // Presets
        JPanel presets = new JPanel(new GridLayout(2, 2, 6, 6));
        presets.setOpaque(false);
        presetPiBtn = new JButton("π ratio");
        preset22_7Btn = new JButton("22/7 ratio");
        prevPresetE = new JButton("e ratio");
        randomIrrBtn = new JButton("Random irrational");

        presetPiBtn.addActionListener(e -> {
            visual.setLockRatio(false);
            visual.setOmega2(visual.omega1 * Math.PI);
            omega2Spinner.setValue(visual.omega2);
        });
        preset22_7Btn.addActionListener(e -> {
            visual.setLockRatio(false);
            visual.setOmega2(visual.omega1 * (22.0 / 7.0));
            omega2Spinner.setValue(visual.omega2);
        });
        prevPresetE.addActionListener(e -> {
            visual.setLockRatio(false);
            visual.setOmega2(visual.omega1 * Math.E);
            omega2Spinner.setValue(visual.omega2);
        });
        randomIrrBtn.addActionListener(e -> {
            visual.setLockRatio(false);
            double r = generateRandomIrrationalRatio();
            visual.setOmega2(visual.omega1 * r);
            omega2Spinner.setValue(visual.omega2);
        });

        presets.add(presetPiBtn);
        presets.add(preset22_7Btn);
        presets.add(prevPresetE);
        presets.add(randomIrrBtn);
        top.add(makeTitledPanel("Presets", presets));

        top.add(Box.createVerticalStrut(8));

        // Measurement window (revolutions)
        JPanel measureRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        measureRow.setOpaque(false);
        JLabel measLab = new JLabel("Measure window (revs):");
        measLab.setForeground(Color.WHITE);
        SpinnerNumberModel model = new SpinnerNumberModel(5, 1, 100, 1);
        JSpinner measureSpinner = new JSpinner(model);
        measureSpinner.addChangeListener(e -> visual.setMeasureWindowRevs(((Number) measureSpinner.getValue()).intValue()));
        measureRow.add(measLab);
        measureRow.add(measureSpinner);
        top.add(measureRow);

        top.add(Box.createVerticalGlue());

        // Fade alpha slider (initialize from visual.getFadeAlpha())
        JSlider fadeSlider = new JSlider(1, 50, (int)(visual.getFadeAlpha() * 100.0));
        fadeSlider.setOpaque(false);
        fadeSlider.addChangeListener(e -> visual.setFadeAlpha(fadeSlider.getValue() / 100f));
        top.add(makeLabeledComponent("Trail Fade (lower = longer)", fadeSlider));

        // Add top to panel
        add(top, BorderLayout.NORTH);

        // bottom diagnostics
        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));

        // toggles
        JPanel toggles = new JPanel(new GridLayout(1, 2, 6, 6));
        toggles.setOpaque(false);
        toggles.add(trailCheck);
        toggles.add(gridCheck);
        bottom.add(toggles);

        // Info / credits
        JLabel credit = new JLabel("<html><small style='color:#AAAAAA'>Double-click canvas to start/pause. Right-click to hide help text.<br/>Tip: compare r=π vs r=22/7.</small></html>");
        credit.setOpaque(false);
        bottom.add(credit);

        add(bottom, BorderLayout.SOUTH);
    }

    private static JComponent makeLabeledComponent(String label, JComponent comp) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setForeground(Color.WHITE);
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        p.add(l, BorderLayout.NORTH);
        p.add(comp, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(1000, 70));
        return p;
    }

    private static JPanel makeTitledPanel(String title, JComponent content) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JLabel l = new JLabel(title);
        l.setForeground(Color.WHITE);
        p.add(l, BorderLayout.NORTH);
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    // generate a "random irrational-like" ratio by combining irrational constants and ratio with sqrt(2), pi, e etc
    private static double generateRandomIrrationalRatio() {
        Random rnd = new Random();
        double[] terms = new double[]{Math.PI, Math.E, Math.sqrt(2), Math.cbrt(5), Math.log(7)};
        double r = 0;
        int n = rnd.nextInt(3) + 2;
        for (int i = 0; i < n; i++) {
            r += terms[rnd.nextInt(terms.length)] * (rnd.nextBoolean() ? 1 : -1) * (0.5 + rnd.nextDouble() * 1.5);
        }
        if (Math.abs(r) < 1e-6) r = Math.PI * (1.0 + rnd.nextDouble());
        return Math.abs(r);
    }
}
