package com.drones.vision.adapter.overlay;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Graphics2D} test double for wave T5's structural, non-pixel
 * assertions: it wraps a real {@link Graphics2D} (so rendering — font
 * metrics, JDK-internal rasterization, etc. — still works exactly as it
 * would in production) and additionally records every {@link #fillRect}
 * and {@link #draw(Shape)} call, plus the {@link Stroke} in effect at each
 * {@code draw} call.
 *
 * <p>Why this exists: {@code DetectionBoxPainter}'s solid border
 * ({@code drawInsetBorder}) is four {@code fillRect} strips, byte-exact and
 * safely pixel-probed; its {@code COASTING} dashed border
 * ({@code drawDashedBorder}) goes through a real {@link Stroke} and {@code
 * draw(Shape)}, whose rasterized pixels are not guaranteed identical across
 * JDKs/fontconfig — adapter-overlay's own determinism convention (see
 * MODULE.md) is to never pixel-probe that path. Recording *which* Graphics2D
 * method a code path invoked, instead of *what pixels* it produced, is the
 * structural assertion the module's convention asks for.
 *
 * <p>Every other {@link Graphics2D} method is a pure delegate to the wrapped
 * instance — this class does not attempt to reimplement AWT, only to
 * observe the two calls the dashed-vs-solid border decision hinges on.
 */
final class RecordingGraphics2D extends Graphics2D {

    record FillRectCall(int x, int y, int width, int height) {
    }

    record DrawCall(Shape shape, Stroke strokeAtCallTime) {
    }

    private final Graphics2D delegate;
    final List<FillRectCall> fillRectCalls = new ArrayList<>();
    final List<DrawCall> drawCalls = new ArrayList<>();

    RecordingGraphics2D(Graphics2D delegate) {
        this.delegate = delegate;
    }

    // -- recorded calls --------------------------------------------------

    @Override
    public void fillRect(int x, int y, int width, int height) {
        fillRectCalls.add(new FillRectCall(x, y, width, height));
        delegate.fillRect(x, y, width, height);
    }

    @Override
    public void draw(Shape s) {
        drawCalls.add(new DrawCall(s, delegate.getStroke()));
        delegate.draw(s);
    }

    @Override
    public void setStroke(Stroke s) {
        delegate.setStroke(s);
    }

    @Override
    public Stroke getStroke() {
        return delegate.getStroke();
    }

    // -- pure delegation below (generated from java.awt.Graphics2D's
    //    abstract method surface; no behavior of its own) ----------------

    @Override
    public void addRenderingHints(java.util.Map<?, ?> p0) {
        delegate.addRenderingHints(p0);
    }

    @Override
    public void clearRect(int p0, int p1, int p2, int p3) {
        delegate.clearRect(p0, p1, p2, p3);
    }

    @Override
    public void clip(java.awt.Shape p0) {
        delegate.clip(p0);
    }

    @Override
    public void clipRect(int p0, int p1, int p2, int p3) {
        delegate.clipRect(p0, p1, p2, p3);
    }

    @Override
    public void copyArea(int p0, int p1, int p2, int p3, int p4, int p5) {
        delegate.copyArea(p0, p1, p2, p3, p4, p5);
    }

    @Override
    public java.awt.Graphics create() {
        return delegate.create();
    }

    @Override
    public void dispose() {
        delegate.dispose();
    }

    @Override
    public void drawArc(int p0, int p1, int p2, int p3, int p4, int p5) {
        delegate.drawArc(p0, p1, p2, p3, p4, p5);
    }

    @Override
    public void drawGlyphVector(java.awt.font.GlyphVector p0, float p1, float p2) {
        delegate.drawGlyphVector(p0, p1, p2);
    }

    @Override
    public boolean drawImage(java.awt.Image p0, java.awt.geom.AffineTransform p1, java.awt.image.ImageObserver p2) {
        return delegate.drawImage(p0, p1, p2);
    }

    @Override
    public void drawImage(java.awt.image.BufferedImage p0, java.awt.image.BufferedImageOp p1, int p2, int p3) {
        delegate.drawImage(p0, p1, p2, p3);
    }

    @Override
    public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8,
                              java.awt.image.ImageObserver p9) {
        return delegate.drawImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9);
    }

    @Override
    public boolean drawImage(java.awt.Image p0, int p1, int p2, java.awt.image.ImageObserver p3) {
        return delegate.drawImage(p0, p1, p2, p3);
    }

    @Override
    public boolean drawImage(java.awt.Image p0, int p1, int p2, java.awt.Color p3, java.awt.image.ImageObserver p4) {
        return delegate.drawImage(p0, p1, p2, p3, p4);
    }

    @Override
    public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, java.awt.image.ImageObserver p5) {
        return delegate.drawImage(p0, p1, p2, p3, p4, p5);
    }

    @Override
    public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8,
                              java.awt.Color p9, java.awt.image.ImageObserver p10) {
        return delegate.drawImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10);
    }

    @Override
    public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, java.awt.Color p5,
                              java.awt.image.ImageObserver p6) {
        return delegate.drawImage(p0, p1, p2, p3, p4, p5, p6);
    }

    @Override
    public void drawLine(int p0, int p1, int p2, int p3) {
        delegate.drawLine(p0, p1, p2, p3);
    }

    @Override
    public void drawOval(int p0, int p1, int p2, int p3) {
        delegate.drawOval(p0, p1, p2, p3);
    }

    @Override
    public void drawPolygon(int[] p0, int[] p1, int p2) {
        delegate.drawPolygon(p0, p1, p2);
    }

    @Override
    public void drawPolyline(int[] p0, int[] p1, int p2) {
        delegate.drawPolyline(p0, p1, p2);
    }

    @Override
    public void drawRenderableImage(java.awt.image.renderable.RenderableImage p0, java.awt.geom.AffineTransform p1) {
        delegate.drawRenderableImage(p0, p1);
    }

    @Override
    public void drawRenderedImage(java.awt.image.RenderedImage p0, java.awt.geom.AffineTransform p1) {
        delegate.drawRenderedImage(p0, p1);
    }

    @Override
    public void drawRoundRect(int p0, int p1, int p2, int p3, int p4, int p5) {
        delegate.drawRoundRect(p0, p1, p2, p3, p4, p5);
    }

    @Override
    public void drawString(java.lang.String p0, float p1, float p2) {
        delegate.drawString(p0, p1, p2);
    }

    @Override
    public void drawString(java.lang.String p0, int p1, int p2) {
        delegate.drawString(p0, p1, p2);
    }

    @Override
    public void drawString(java.text.AttributedCharacterIterator p0, float p1, float p2) {
        delegate.drawString(p0, p1, p2);
    }

    @Override
    public void drawString(java.text.AttributedCharacterIterator p0, int p1, int p2) {
        delegate.drawString(p0, p1, p2);
    }

    @Override
    public void fill(java.awt.Shape p0) {
        delegate.fill(p0);
    }

    @Override
    public void fillArc(int p0, int p1, int p2, int p3, int p4, int p5) {
        delegate.fillArc(p0, p1, p2, p3, p4, p5);
    }

    @Override
    public void fillOval(int p0, int p1, int p2, int p3) {
        delegate.fillOval(p0, p1, p2, p3);
    }

    @Override
    public void fillPolygon(int[] p0, int[] p1, int p2) {
        delegate.fillPolygon(p0, p1, p2);
    }

    @Override
    public void fillRoundRect(int p0, int p1, int p2, int p3, int p4, int p5) {
        delegate.fillRoundRect(p0, p1, p2, p3, p4, p5);
    }

    @Override
    public java.awt.Color getBackground() {
        return delegate.getBackground();
    }

    @Override
    public java.awt.Shape getClip() {
        return delegate.getClip();
    }

    @Override
    public java.awt.Rectangle getClipBounds() {
        return delegate.getClipBounds();
    }

    @Override
    public java.awt.Color getColor() {
        return delegate.getColor();
    }

    @Override
    public java.awt.Composite getComposite() {
        return delegate.getComposite();
    }

    @Override
    public java.awt.GraphicsConfiguration getDeviceConfiguration() {
        return delegate.getDeviceConfiguration();
    }

    @Override
    public java.awt.Font getFont() {
        return delegate.getFont();
    }

    @Override
    public java.awt.FontMetrics getFontMetrics(java.awt.Font p0) {
        return delegate.getFontMetrics(p0);
    }

    @Override
    public java.awt.font.FontRenderContext getFontRenderContext() {
        return delegate.getFontRenderContext();
    }

    @Override
    public java.awt.Paint getPaint() {
        return delegate.getPaint();
    }

    @Override
    public java.lang.Object getRenderingHint(java.awt.RenderingHints.Key p0) {
        return delegate.getRenderingHint(p0);
    }

    @Override
    public java.awt.RenderingHints getRenderingHints() {
        return delegate.getRenderingHints();
    }

    @Override
    public java.awt.geom.AffineTransform getTransform() {
        return delegate.getTransform();
    }

    @Override
    public boolean hit(java.awt.Rectangle p0, java.awt.Shape p1, boolean p2) {
        return delegate.hit(p0, p1, p2);
    }

    @Override
    public void rotate(double p0, double p1, double p2) {
        delegate.rotate(p0, p1, p2);
    }

    @Override
    public void rotate(double p0) {
        delegate.rotate(p0);
    }

    @Override
    public void scale(double p0, double p1) {
        delegate.scale(p0, p1);
    }

    @Override
    public void setBackground(java.awt.Color p0) {
        delegate.setBackground(p0);
    }

    @Override
    public void setClip(int p0, int p1, int p2, int p3) {
        delegate.setClip(p0, p1, p2, p3);
    }

    @Override
    public void setClip(java.awt.Shape p0) {
        delegate.setClip(p0);
    }

    @Override
    public void setColor(java.awt.Color p0) {
        delegate.setColor(p0);
    }

    @Override
    public void setComposite(java.awt.Composite p0) {
        delegate.setComposite(p0);
    }

    @Override
    public void setFont(java.awt.Font p0) {
        delegate.setFont(p0);
    }

    @Override
    public void setPaint(java.awt.Paint p0) {
        delegate.setPaint(p0);
    }

    @Override
    public void setPaintMode() {
        delegate.setPaintMode();
    }

    @Override
    public void setRenderingHint(java.awt.RenderingHints.Key p0, java.lang.Object p1) {
        delegate.setRenderingHint(p0, p1);
    }

    @Override
    public void setRenderingHints(java.util.Map<?, ?> p0) {
        delegate.setRenderingHints(p0);
    }

    @Override
    public void setTransform(java.awt.geom.AffineTransform p0) {
        delegate.setTransform(p0);
    }

    @Override
    public void setXORMode(java.awt.Color p0) {
        delegate.setXORMode(p0);
    }

    @Override
    public void shear(double p0, double p1) {
        delegate.shear(p0, p1);
    }

    @Override
    public void transform(java.awt.geom.AffineTransform p0) {
        delegate.transform(p0);
    }

    @Override
    public void translate(double p0, double p1) {
        delegate.translate(p0, p1);
    }

    @Override
    public void translate(int p0, int p1) {
        delegate.translate(p0, p1);
    }
}
