package com.amore;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextAttribute;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import javax.imageio.ImageIO;

/** Renders the shoutout profile card posted for Active Check winners. */
public final class ProfileCard {

    private ProfileCard() {
    }

    /** Layout is in logical units; the PNG is rendered at {@code SCALE}x for crisp edges on high-DPI screens. */
    private static final int W = 400;
    private static final int H = 488;
    private static final int SCALE = 2;
    private static final float RADIUS = 30f;
    private static final int BANNER_H = 150;
    private static final int AVATAR = 120;
    private static final float AVATAR_CX = W / 2f;
    private static final float AVATAR_CY = BANNER_H;

    private static final String FONT = "SansSerif";
    private static final Color BG_TOP = new Color(30, 20, 41);
    private static final Color BG_BOTTOM = new Color(13, 9, 20);
    private static final Color PINK = new Color(255, 112, 186);
    private static final Color ROSE = new Color(255, 196, 224);
    private static final Color VIOLET = new Color(150, 88, 255);
    private static final Color LILAC = new Color(206, 180, 255);
    private static final Color PERIWINKLE = new Color(118, 146, 255);
    private static final Color MUTED = new Color(196, 182, 216);

    /** PNG bytes of the card, or null if rendering failed. Blocking: downloads the avatar and banner. */
    public static byte[] render(String username, String avatarUrl, String bannerUrl, int sparks) {
        try {
            BufferedImage card = new BufferedImage(W * SCALE, H * SCALE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = card.createGraphics();
            hints(g);
            g.scale(SCALE, SCALE);

            Font nameFont = new Font(FONT, Font.BOLD, 30);
            String name = toDrawableName(username, nameFont);

            paintBackground(g);
            paintAurora(g);
            paintBanner(g, bannerUrl == null ? null : fetch(bannerUrl + "?size=1024"));
            paintStars(g, name);
            paintAvatar(g, avatarUrl == null ? null : fetch(avatarUrl + "?size=512"), name);
            paintName(g, name, nameFont);
            paintStats(g, sparks);
            paintCollection(g);
            paintFooter(g);

            // Antialiased rounded corners: DstIn keeps only the card shape's coverage.
            Shape outline = new RoundRectangle2D.Float(0, 0, W, H, RADIUS * 2, RADIUS * 2);
            g.setComposite(AlphaComposite.DstIn);
            g.setColor(Color.WHITE);
            g.fill(outline);
            g.setComposite(AlphaComposite.SrcOver);
            paintBorder(g);

            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(card, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Discord names often use "fancy" Unicode (math bold/script, fullwidth, circled letters) that the card font
     * has no glyphs for, which renders as tofu boxes. NFKC folds those to plain letters; anything the font still
     * cannot draw is dropped.
     */
    static String toDrawableName(String username, Font font) {
        String folded = Normalizer.normalize(username == null ? "" : username, Normalizer.Form.NFKC)
                .replaceAll("[^\\p{L}\\p{N} .,_\\-~*|!?'\"♡♥ʚɞ⋆✧✦]+", "");
        StringBuilder sb = new StringBuilder(folded.length());
        folded.codePoints().filter(font::canDisplay).forEach(sb::appendCodePoint);
        String name = sb.toString().replaceAll(" {2,}", " ").trim();
        return name.isEmpty() ? "Player" : name;
    }

    // ---------------------------------------------------------------- sections

    private static void paintBackground(Graphics2D g) {
        g.setPaint(new LinearGradientPaint(0, 0, 0, H, new float[]{0f, 1f}, new Color[]{BG_TOP, BG_BOTTOM}));
        g.fillRect(0, 0, W, H);
    }

    /** Banner rendered on its own layer whose alpha fades out, so it melts into the aurora behind it. */
    private static void paintBanner(Graphics2D g, BufferedImage banner) {
        BufferedImage layer = new BufferedImage(W * SCALE, BANNER_H * SCALE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D b = layer.createGraphics();
        hints(b);
        b.scale(SCALE, SCALE);
        if (banner != null) {
            drawCover(b, banner, 0, 0, W, BANNER_H);
            // Gentle tint so any banner sits in the card's palette.
            b.setPaint(new LinearGradientPaint(0, 0, W, BANNER_H, new float[]{0f, 1f},
                    new Color[]{alpha(PINK, 40), alpha(VIOLET, 50)}));
            b.fillRect(0, 0, W, BANNER_H);
        } else {
            b.setPaint(new LinearGradientPaint(0, 0, W, BANNER_H, new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(255, 128, 196), new Color(196, 110, 255), new Color(104, 92, 238)}));
            b.fillRect(0, 0, W, BANNER_H);
            glow(b, 70, 10, 170, Color.WHITE, 55);
            glow(b, 360, 130, 150, PINK, 110);
            glow(b, 250, -20, 120, PERIWINKLE, 90);
        }
        b.setComposite(AlphaComposite.DstIn);
        b.setPaint(new LinearGradientPaint(0, 0, 0, BANNER_H, new float[]{0f, 0.35f, 0.75f, 1f},
                new Color[]{Color.WHITE, Color.WHITE, alpha(Color.WHITE, 90), alpha(Color.WHITE, 0)}));
        b.fillRect(0, 0, W, BANNER_H);
        b.dispose();
        g.drawImage(layer, 0, 0, W, BANNER_H, null);
    }

    private static void paintAurora(Graphics2D g) {
        glow(g, 20, 300, 240, PINK, 50);
        glow(g, 390, 430, 270, VIOLET, 70);
        glow(g, 340, 230, 170, PERIWINKLE, 28);
        glow(g, 60, 480, 160, LILAC, 22);
        glow(g, 70, 165, 210, PINK, 38);
        glow(g, 330, 165, 210, VIOLET, 42);
    }

    /** Star dust is seeded by name so each member gets a stable sky. */
    private static void paintStars(Graphics2D g, String name) {
        Random rnd = new Random(name.hashCode());
        for (int i = 0; i < 60; i++) {
            double x = rnd.nextDouble() * W;
            double y = BANNER_H + rnd.nextDouble() * (H - BANNER_H);
            double r = 0.4 + rnd.nextDouble() * 1.0;
            Color c = rnd.nextInt(3) == 0 ? ROSE : Color.WHITE;
            g.setColor(alpha(c, 30 + rnd.nextInt(110)));
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }

        sparkle(g, 352, 38, 9, Color.WHITE, 230);
        sparkle(g, 328, 70, 4.5, Color.WHITE, 170);
        sparkle(g, 56, 44, 6, Color.WHITE, 190);
        sparkle(g, 84, 112, 3.5, ROSE, 160);
        sparkle(g, 372, 196, 6, LILAC, 150);
        sparkle(g, 30, 214, 4.5, ROSE, 130);
    }

    private static void paintAvatar(Graphics2D g, BufferedImage avatar, String name) {
        float cx = AVATAR_CX;
        float cy = AVATAR_CY;
        float r = AVATAR / 2f;

        glow(g, cx, cy + 10, r + 40, Color.BLACK, 110);
        glow(g, cx - 18, cy - 6, r + 46, PINK, 95);
        glow(g, cx + 22, cy + 10, r + 40, VIOLET, 85);

        float ring = r + 7;
        g.setPaint(new LinearGradientPaint(cx - ring, cy - ring, cx + ring, cy + ring,
                new float[]{0f, 0.45f, 1f}, new Color[]{ROSE, PINK, VIOLET}));
        g.fill(circle(cx, cy, ring));
        g.setColor(bgAt(BANNER_H));
        g.fill(circle(cx, cy, r + 3.5f));

        int px = AVATAR * SCALE;
        BufferedImage round = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D a = round.createGraphics();
        hints(a);
        a.fill(new Ellipse2D.Float(0, 0, px, px));
        a.setComposite(AlphaComposite.SrcIn);
        if (avatar != null) {
            drawCover(a, avatar, 0, 0, px, px);
        } else {
            a.setPaint(new LinearGradientPaint(0, 0, px, px, new float[]{0f, 1f}, new Color[]{PINK, VIOLET}));
            a.fillRect(0, 0, px, px);
            a.setComposite(AlphaComposite.SrcOver);
            String initial = new String(Character.toChars(Character.toUpperCase(name.codePointAt(0))));
            Font f = new Font(FONT, Font.BOLD, px / 2);
            Rectangle2D b = f.getStringBounds(initial, a.getFontRenderContext());
            a.setFont(f);
            a.setColor(Color.WHITE);
            a.drawString(initial, (float) (px / 2.0 - b.getCenterX()), (float) (px / 2.0 + f.getSize2D() * 0.36));
        }
        a.dispose();
        g.drawImage(round, Math.round(cx - r), Math.round(cy - r), AVATAR, AVATAR, null);

        // Glass sheen on the upper half of the avatar.
        g.setPaint(new LinearGradientPaint(0, cy - r, 0, cy, new float[]{0f, 1f},
                new Color[]{alpha(Color.WHITE, 38), alpha(Color.WHITE, 0)}));
        g.fill(new Ellipse2D.Float(cx - r + 8, cy - r + 3, AVATAR - 16, r));

        // Heart badge.
        float bx = cx + r * 0.72f;
        float by = cy + r * 0.72f;
        glow(g, bx, by, 26, PINK, 120);
        g.setColor(bgAt(BANNER_H));
        g.fill(circle(bx, by, 17));
        g.setPaint(new LinearGradientPaint(bx - 14, by - 14, bx + 14, by + 14, new float[]{0f, 1f},
                new Color[]{new Color(255, 150, 205), new Color(236, 72, 168)}));
        g.fill(circle(bx, by, 14));
        g.setColor(Color.WHITE);
        g.fill(heart(bx, by + 0.5, 7));
    }

    private static void paintName(Graphics2D g, String name, Font base) {
        float max = W - 64;
        Font font = base;
        while (width(g, name, font) > max && font.getSize2D() > 18) {
            font = font.deriveFont(font.getSize2D() - 1);
        }
        String shown = name;
        if (width(g, name, font) > max) {
            String cut = name;
            while (cut.length() > 1 && width(g, cut + "…", font) > max) {
                cut = cut.substring(0, cut.offsetByCodePoints(cut.length(), -1));
            }
            shown = cut.trim() + "…";
        }

        float baseline = 256;
        Rectangle2D b = font.getStringBounds(shown, g.getFontRenderContext());
        float x = (float) (W / 2.0 - b.getCenterX());

        textGlow(g, shown, font, x, baseline, PINK, 9, 0.75f);
        g.setFont(font);
        g.setColor(alpha(Color.BLACK, 120));
        g.drawString(shown, x, baseline + 1.5f);
        g.setPaint(new LinearGradientPaint(0, baseline + (float) b.getY(), 0, baseline,
                new float[]{0f, 0.55f, 1f}, new Color[]{Color.WHITE, Color.WHITE, ROSE}));
        g.drawString(shown, x, baseline);

        Font tag = tracked(new Font(FONT, Font.BOLD, 10), 0.32f);
        String label = "COMMUNITY SPOTLIGHT";
        Rectangle2D tb = tag.getStringBounds(label, g.getFontRenderContext());
        float tx = (float) (W / 2.0 - tb.getCenterX());
        g.setFont(tag);
        g.setColor(alpha(ROSE, 215));
        g.drawString(label, tx, 278);
        sparkle(g, tx - 13, 274.5, 4.5, PINK, 230);
        sparkle(g, (float) (tx + tb.getMaxX() + 11), 274.5, 4.5, VIOLET, 230);
    }

    private static void paintStats(Graphics2D g, int sparks) {
        float y = 298;
        float h = 76;
        float gap = 12;
        float w = (W - 48 - gap) / 2f;

        float x1 = 24;
        glassPanel(g, x1, y, w, h, 20, PINK);
        iconBadge(g, x1 + 34, y + h / 2, ROSE, PINK);
        g.setColor(Color.WHITE);
        g.fill(sparklePath(x1 + 34, y + h / 2, 9.5));
        statText(g, String.format(Locale.US, "%,d", sparks), "SPARKS", x1 + 64, y, w - 76);

        float x2 = x1 + w + gap;
        glassPanel(g, x2, y, w, h, 20, VIOLET);
        iconBadge(g, x2 + 34, y + h / 2, LILAC, VIOLET);
        g.setColor(Color.WHITE);
        g.fill(crown(x2 + 34, y + h / 2 + 1, 10));
        statText(g, "Winner", "EVENT", x2 + 64, y, w - 76);
    }

    private static void paintCollection(Graphics2D g) {
        float x = 24;
        float y = 386;
        float w = W - 48;
        float h = 58;
        glassPanel(g, x, y, w, h, 18, LILAC);
        iconBadge(g, x + 34, y + h / 2, new Color(170, 200, 255), PERIWINKLE);
        g.setColor(Color.WHITE);
        g.fill(gem(x + 34, y + h / 2, 9));

        g.setFont(new Font(FONT, Font.BOLD, 15));
        g.setColor(Color.WHITE);
        g.drawString("Game Collection", x + 64, y + h / 2 + 5.5f);

        float pw = 48;
        float ph = 28;
        float px = x + w - pw - 14;
        float py = y + (h - ph) / 2;
        glow(g, px + pw / 2, py + ph / 2, 40, PINK, 70);
        g.setPaint(new LinearGradientPaint(px, py, px + pw, py + ph, new float[]{0f, 1f}, new Color[]{PINK, VIOLET}));
        g.fill(new RoundRectangle2D.Float(px, py, pw, ph, ph, ph));
        g.setPaint(new LinearGradientPaint(0, py, 0, py + ph / 2, new float[]{0f, 1f},
                new Color[]{alpha(Color.WHITE, 70), alpha(Color.WHITE, 0)}));
        g.fill(new RoundRectangle2D.Float(px + 1, py + 1, pw - 2, ph / 2, ph / 2, ph / 2));
        Font f = new Font(FONT, Font.BOLD, 14);
        Rectangle2D b = f.getStringBounds("+3", g.getFontRenderContext());
        g.setFont(f);
        g.setColor(Color.WHITE);
        g.drawString("+3", (float) (px + pw / 2 - b.getCenterX()), py + ph / 2 + 5f);
    }

    private static void paintFooter(Graphics2D g) {
        Font f = tracked(new Font(FONT, Font.BOLD, 9), 0.5f);
        String text = "AMORA";
        Rectangle2D b = f.getStringBounds(text, g.getFontRenderContext());
        float x = (float) (W / 2.0 - b.getCenterX());
        float baseline = 470;
        g.setFont(f);
        g.setColor(alpha(MUTED, 120));
        g.drawString(text, x, baseline);
        g.setColor(alpha(PINK, 150));
        g.fill(heart(x - 12, baseline - 3.5, 4));
        g.fill(heart(x + b.getMaxX() + 10, baseline - 3.5, 4));
    }

    private static void paintBorder(Graphics2D g) {
        float inset = 0.75f;
        g.setStroke(new BasicStroke(1.5f));
        g.setPaint(new LinearGradientPaint(0, 0, W, H, new float[]{0f, 0.5f, 1f},
                new Color[]{alpha(ROSE, 190), alpha(Color.WHITE, 40), alpha(VIOLET, 170)}));
        g.draw(new RoundRectangle2D.Float(inset, inset, W - inset * 2, H - inset * 2, RADIUS * 2, RADIUS * 2));
    }

    // ---------------------------------------------------------------- components

    private static void glassPanel(Graphics2D g, float x, float y, float w, float h, float r, Color accent) {
        RoundRectangle2D.Float shape = new RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2);
        g.setPaint(new LinearGradientPaint(x, y, x, y + h, new float[]{0f, 1f},
                new Color[]{alpha(Color.WHITE, 20), alpha(Color.WHITE, 8)}));
        g.fill(shape);
        g.setPaint(new LinearGradientPaint(x, y, x + w, y + h, new float[]{0f, 1f},
                new Color[]{alpha(accent, 26), alpha(accent, 0)}));
        g.fill(shape);
        g.setStroke(new BasicStroke(1.2f));
        g.setPaint(new LinearGradientPaint(x, y, x, y + h, new float[]{0f, 1f},
                new Color[]{alpha(Color.WHITE, 70), alpha(Color.WHITE, 14)}));
        g.draw(shape);
    }

    private static void iconBadge(Graphics2D g, float cx, float cy, Color light, Color dark) {
        glow(g, cx, cy, 34, dark, 110);
        g.setPaint(new LinearGradientPaint(cx - 19, cy - 19, cx + 19, cy + 19, new float[]{0f, 1f},
                new Color[]{light, dark}));
        g.fill(circle(cx, cy, 19));
        g.setPaint(new LinearGradientPaint(0, cy - 19, 0, cy, new float[]{0f, 1f},
                new Color[]{alpha(Color.WHITE, 80), alpha(Color.WHITE, 0)}));
        g.fill(new Ellipse2D.Float(cx - 13, cy - 17, 26, 16));
    }

    private static void statText(Graphics2D g, String value, String label, float x, float panelY, float maxWidth) {
        Font f = new Font(FONT, Font.BOLD, 22);
        while (width(g, value, f) > maxWidth && f.getSize2D() > 12) {
            f = f.deriveFont(f.getSize2D() - 1);
        }
        g.setFont(f);
        g.setColor(Color.WHITE);
        g.drawString(value, x, panelY + 39);
        g.setFont(tracked(new Font(FONT, Font.BOLD, 1).deriveFont(9.5f), 0.28f));
        g.setColor(alpha(MUTED, 200));
        g.drawString(label, x, panelY + 56);
    }

    private static void sparkle(Graphics2D g, double x, double y, double size, Color color, int alpha) {
        glow(g, (float) x, (float) y, (float) (size * 2.6), color, alpha / 3);
        g.setColor(alpha(color, alpha));
        g.fill(sparklePath(x, y, size));
    }

    /** Soft round light: radial fade from {@code alpha} at the centre to transparent at {@code radius}. */
    private static void glow(Graphics2D g, float cx, float cy, float radius, Color color, int alpha) {
        g.setPaint(new RadialGradientPaint(cx, cy, radius, new float[]{0f, 0.4f, 1f},
                new Color[]{alpha(color, alpha), alpha(color, alpha * 2 / 5), alpha(color, 0)}));
        g.fill(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2));
    }

    /** Gaussian-blurred copy of the text, composited behind it as a neon halo. */
    private static void textGlow(Graphics2D g, String text, Font font, float x, float baseline,
                                 Color color, float radius, float opacity) {
        Rectangle2D b = font.getStringBounds(text, g.getFontRenderContext());
        int pad = (int) Math.ceil(radius * 2);
        int lw = (int) Math.ceil(b.getWidth()) + pad * 2;
        int lh = (int) Math.ceil(b.getHeight()) + pad * 2;
        BufferedImage layer = new BufferedImage(lw * SCALE, lh * SCALE, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D lg = layer.createGraphics();
        hints(lg);
        lg.scale(SCALE, SCALE);
        lg.setFont(font);
        lg.setColor(color);
        lg.drawString(text, pad - (float) b.getX(), pad - (float) b.getY());
        lg.dispose();

        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
        g.drawImage(blur(layer, Math.round(radius * SCALE)),
                Math.round(x + (float) b.getX() - pad), Math.round(baseline + (float) b.getY() - pad), lw, lh, null);
        g.setComposite(old);
    }

    // ---------------------------------------------------------------- shapes

    private static Ellipse2D circle(float cx, float cy, float r) {
        return new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2);
    }

    private static Path2D sparklePath(double x, double y, double size) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x, y - size);
        p.quadTo(x, y - size / 5, x + size, y);
        p.quadTo(x, y + size / 5, x, y + size);
        p.quadTo(x, y + size / 5, x - size, y);
        p.quadTo(x, y - size / 5, x, y - size);
        p.closePath();
        return p;
    }

    private static Path2D heart(double cx, double cy, double s) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(cx, cy + s * 0.9);
        p.curveTo(cx - s * 1.35, cy + s * 0.05, cx - s * 0.8, cy - s * 1.1, cx, cy - s * 0.42);
        p.curveTo(cx + s * 0.8, cy - s * 1.1, cx + s * 1.35, cy + s * 0.05, cx, cy + s * 0.9);
        p.closePath();
        return p;
    }

    private static Path2D crown(double cx, double cy, double s) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(cx - s, cy + s * 0.55);
        p.lineTo(cx - s, cy - s * 0.45);
        p.lineTo(cx - s * 0.5, cy + s * 0.05);
        p.lineTo(cx, cy - s * 0.7);
        p.lineTo(cx + s * 0.5, cy + s * 0.05);
        p.lineTo(cx + s, cy - s * 0.45);
        p.lineTo(cx + s, cy + s * 0.55);
        p.closePath();
        double d = s * 0.32;
        p.append(new Ellipse2D.Double(cx - s - d / 2, cy - s * 0.45 - d, d, d), false);
        p.append(new Ellipse2D.Double(cx - d / 2, cy - s * 0.7 - d, d, d), false);
        p.append(new Ellipse2D.Double(cx + s - d / 2, cy - s * 0.45 - d, d, d), false);
        return p;
    }

    private static Path2D gem(double cx, double cy, double s) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(cx - s, cy - s * 0.3);
        p.lineTo(cx - s * 0.55, cy - s * 0.8);
        p.lineTo(cx + s * 0.55, cy - s * 0.8);
        p.lineTo(cx + s, cy - s * 0.3);
        p.lineTo(cx, cy + s * 0.95);
        p.closePath();
        return p;
    }

    // ---------------------------------------------------------------- utils

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    }

    private static BufferedImage fetch(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (InputStream in = c.getInputStream()) {
                return ImageIO.read(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Scales {@code img} to cover the box (centre-cropped, aspect preserved). */
    private static void drawCover(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
        double s = Math.max((double) w / img.getWidth(), (double) h / img.getHeight());
        int dw = (int) Math.ceil(img.getWidth() * s);
        int dh = (int) Math.ceil(img.getHeight() * s);
        g.drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
    }

    private static BufferedImage blur(BufferedImage src, int radius) {
        int size = radius * 2 + 1;
        float[] weights = new float[size];
        double sigma = radius / 2.0;
        double sum = 0;
        for (int i = 0; i < size; i++) {
            int d = i - radius;
            weights[i] = (float) Math.exp(-(d * d) / (2 * sigma * sigma));
            sum += weights[i];
        }
        for (int i = 0; i < size; i++) {
            weights[i] /= (float) sum;
        }
        BufferedImage horizontal = new ConvolveOp(new Kernel(size, 1, weights), ConvolveOp.EDGE_NO_OP, null).filter(src, null);
        return new ConvolveOp(new Kernel(1, size, weights), ConvolveOp.EDGE_NO_OP, null).filter(horizontal, null);
    }

    private static Font tracked(Font font, float tracking) {
        return font.deriveFont(Map.of(TextAttribute.TRACKING, tracking));
    }

    private static double width(Graphics2D g, String text, Font font) {
        return font.getStringBounds(text, g.getFontRenderContext()).getWidth();
    }

    /** Background colour at height {@code y}, so overlays blend seamlessly into the base gradient. */
    private static Color bgAt(float y) {
        float t = Math.max(0f, Math.min(1f, y / H));
        return new Color(
                Math.round(BG_TOP.getRed() + (BG_BOTTOM.getRed() - BG_TOP.getRed()) * t),
                Math.round(BG_TOP.getGreen() + (BG_BOTTOM.getGreen() - BG_TOP.getGreen()) * t),
                Math.round(BG_TOP.getBlue() + (BG_BOTTOM.getBlue() - BG_TOP.getBlue()) * t));
    }

    private static Color alpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
