/*******************************************************************************
 * Copyright (c) 2010 Nicolas Roduit.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 *     Marcelo Porto
 ******************************************************************************/

package org.weasis.core.ui.util;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.RenderedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.swing.JComponent;
import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.MathUtil;
import org.weasis.core.api.image.LayoutConstraints;
import org.weasis.core.api.media.data.ImageElement;
import org.weasis.core.api.service.AuditLog;
import org.weasis.core.ui.Messages;
import org.weasis.core.ui.editor.image.ExportImage;

public class ImagePrint implements Printable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImagePrint.class);

    private Point printLoc;
    private PrintOptions printOptions;
    private ExportLayout<? extends ImageElement> layout;

    public ImagePrint(ExportLayout<? extends ImageElement> layout, PrintOptions printOptions) {
        this.layout = layout;
        this.printLoc = new Point(0, 0);
        this.printOptions = printOptions == null ? new PrintOptions(true) : printOptions;
    }

    public void setPrintLocation(Point d) {
        printLoc = d;
    }

    public Point getPrintLocation() {
        return printLoc;
    }

    public void print() {
        PrintRequestAttributeSet aset = new HashPrintRequestAttributeSet();
        PrinterJob pj = PrinterJob.getPrinterJob();
        pj.setPrintable(this);

        // Get page format from the printer
        if (pj.printDialog(aset)) {
            try {
                pj.print(aset);
            } catch (Exception e) {
                // check for the annoying 'Printer is not accepting job' error.
                if (e.getMessage().indexOf("accepting job") != -1) { //$NON-NLS-1$
                    // recommend prompting the user at this point if they want to force it
                    // so they'll know there may be a problem.
                    int response = JOptionPane.showConfirmDialog(null, Messages.getString("ImagePrint.issue_desc"), //$NON-NLS-1$
                        Messages.getString("ImagePrint.status"), JOptionPane.YES_NO_OPTION, //$NON-NLS-1$
                        JOptionPane.WARNING_MESSAGE);

                    if (response == 0) {
                        try {
                            // try printing again but ignore the not-accepting-jobs attribute
                            ForcedAcceptPrintService.setupPrintJob(pj); // add secret ingredient
                            pj.print(aset);
                            LOGGER.info("Bypass Printer is not accepting job"); //$NON-NLS-1$
                        } catch (PrinterException ex) {
                            AuditLog.logError(LOGGER, e, "Printer exception"); //$NON-NLS-1$
                        }
                    }
                } else {
                    AuditLog.logError(LOGGER, e, "Print exception"); //$NON-NLS-1$
                }
            }
        }
    }

    public static boolean disableDoubleBuffering(JComponent c) {
        if (c == null) {
            return false;
        }
        c.setDoubleBuffered(false);
        return c.isDoubleBuffered();
    }

    public static void restoreDoubleBuffering(JComponent c, boolean wasBuffered) {
        if (c != null) {
            c.setDoubleBuffered(wasBuffered);
        }
    }

    @Override
    public int print(Graphics g, PageFormat f, int pageIndex) {
        if (pageIndex >= 1) {
            return Printable.NO_SUCH_PAGE;
        }
        printImage((Graphics2D) g, f);
        return Printable.PAGE_EXISTS;
    }

    public void printImage(Graphics2D g2d, PageFormat f) {
        if ((layout == null) || (g2d == null)) {
            return;
        }
        Dimension dimGrid = layout.layoutModel.getGridSize();
        Point2D.Double placeholder = new Point2D.Double(f.getImageableWidth() - (dimGrid.width - 1) * 5,
            f.getImageableHeight() - (dimGrid.height - 1) * 5);

        int lastx = 0;
        double lastwx = 0.0;
        double[] lastwy = new double[dimGrid.width];
        double wx = 0.0;

        final Map<LayoutConstraints, Component> elements = layout.layoutModel.getConstraints();
        Iterator<Entry<LayoutConstraints, Component>> enumVal = elements.entrySet().iterator();
        while (enumVal.hasNext()) {
            Entry<LayoutConstraints, Component> e = enumVal.next();
            LayoutConstraints key = e.getKey();
            Component value = e.getValue();

            ExportImage<? extends ImageElement> image = null;
            Point2D.Double pad = new Point2D.Double(0.0, 0.0);

            if (value instanceof ExportImage) {
                image = (ExportImage) value;
                formatImage(image, key, placeholder, pad);
            }

            if (key.gridx == 0) {
                wx = 0.0;
            } else if (lastx < key.gridx) {
                wx += lastwx;
            }
            double wy = lastwy[key.gridx];
            double x = printLoc.x + f.getImageableX() + (placeholder.x * wx)
                + (MathUtil.isEqualToZero(wx) ? 0 : key.gridx * 5) + pad.x;
            double y = printLoc.y + f.getImageableY() + (placeholder.y * wy)
                + (MathUtil.isEqualToZero(wy) ? 0 : key.gridy * 5) + pad.y;
            lastx = key.gridx;
            lastwx = key.weightx;
            for (int i = key.gridx; i < key.gridx + key.gridwidth; i++) {
                lastwy[i] += key.weighty;
            }

            if (image != null) {
                // Set us to the upper left corner
                g2d.translate(x, y);
                boolean wasBuffered = disableDoubleBuffering(image);
                g2d.setClip(image.getBounds());
                image.draw(g2d);
                restoreDoubleBuffering(image, wasBuffered);
                g2d.translate(-x, -y);
            }
        }
    }

    private void formatImage(ExportImage<? extends ImageElement> image, LayoutConstraints key,
        Point2D.Double placeholder, Point2D.Double pad) {
        if (!printOptions.getHasAnnotations() && image.getInfoLayer().isVisible()) {
            image.getInfoLayer().setVisible(false);
        }

        Rectangle2D originSize = (Rectangle2D) image.getActionValue("origin.image.bound"); //$NON-NLS-1$
        Point2D originCenter = (Point2D) image.getActionValue("origin.center"); //$NON-NLS-1$
        Double originZoom = (Double) image.getActionValue("origin.zoom"); //$NON-NLS-1$
        RenderedImage img = image.getSourceImage();
        if (img != null && originCenter != null && originZoom != null) {
            boolean bestfit = originZoom <= 0.0;
            double canvasWidth;
            double canvasHeight;
            if (bestfit || originSize == null) {
                canvasWidth = img.getWidth() * image.getImage().getRescaleX();
                canvasHeight = img.getHeight() * image.getImage().getRescaleY();
            } else {
                canvasWidth = originSize.getWidth() / originZoom;
                canvasHeight = originSize.getHeight() / originZoom;
            }
            double scaleCanvas =
                Math.min(placeholder.x * key.weightx / canvasWidth, placeholder.y * key.weighty / canvasHeight);

            // Set the print area in pixel
            double cw = canvasWidth * scaleCanvas;
            double ch = canvasHeight * scaleCanvas;
            image.setSize((int) (cw + 0.5), (int) (ch + 0.5));

            if (printOptions.isCenter()) {
                pad.x = (placeholder.x * key.weightx - cw) * 0.5;
                pad.y = (placeholder.y * key.weighty - ch) * 0.5;
            } else {
                pad.x = 0.0;
                pad.y = 0.0;
            }

            double scaleFactor = Math.min(cw / canvasWidth, ch / canvasHeight);
            // Resize in best fit window
            image.zoom(scaleFactor);
            if (bestfit) {
                image.center();
            } else {
                image.setCenter(originCenter.getX(), originCenter.getY());
            }

            int dpi = printOptions.getDpi() == null ? 150 : printOptions.getDpi().getDpi();
            double ratioFromDPI = dpi * scaleFactor / 72.0;
            image.setImagePrintingResolution(ratioFromDPI);
        }
    }
}
