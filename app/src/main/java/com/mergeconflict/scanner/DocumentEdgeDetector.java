package com.mergeconflict.scanner;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

public final class DocumentEdgeDetector {

    private DocumentEdgeDetector() {}

    public static final class CropProposal {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final int confidence;
        public final float aspectRatio;

        CropProposal(
                float left,
                float top,
                float right,
                float bottom,
                int confidence,
                float aspectRatio) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.confidence = confidence;
            this.aspectRatio = aspectRatio;
        }

        public float widthFraction() {
            return right - left;
        }

        public float heightFraction() {
            return bottom - top;
        }

        @NonNull
        public String formatLabel() {
            float ratio = aspectRatio;
            if (Math.abs(ratio - 1.4142f) <= 0.10f) {
                return "format proche A4";
            }
            if (Math.abs(ratio - 1.2941f) <= 0.08f) {
                return "format proche Lettre";
            }
            return String.format(Locale.FRANCE, "ratio %.2f", ratio);
        }
    }

    @Nullable
    public static CropProposal detect(@NonNull Bitmap bitmap) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        if (width < 120 || height < 120) return null;

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int[] gray = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            gray[i] = (r * 30 + g * 59 + b * 11) / 100;
        }

        double[] columnScore = new double[width];
        double[] rowScore = new double[height];

        int centralTop = Math.max(1, height / 10);
        int centralBottom = Math.min(height - 2, height - height / 10);
        int centralLeft = Math.max(1, width / 10);
        int centralRight = Math.min(width - 2, width - width / 10);

        for (int y = 1; y < height - 1; y++) {
            int base = y * width;
            for (int x = 1; x < width - 1; x++) {
                int gx = Math.abs(gray[base + x + 1] - gray[base + x - 1]);
                int gy = Math.abs(gray[(y + 1) * width + x] - gray[(y - 1) * width + x]);
                int magnitude = gx + gy;

                if (y >= centralTop && y <= centralBottom) {
                    columnScore[x] += magnitude;
                }
                if (x >= centralLeft && x <= centralRight) {
                    rowScore[y] += magnitude;
                }
            }
        }

        columnScore = smooth(columnScore, Math.max(2, width / 140));
        rowScore = smooth(rowScore, Math.max(2, height / 140));

        int leftStart = Math.max(2, Math.round(width * 0.03f));
        int leftEnd = Math.max(leftStart + 1, Math.round(width * 0.45f));
        int rightStart = Math.min(width - 3, Math.round(width * 0.55f));
        int rightEnd = Math.min(width - 2, Math.round(width * 0.97f));
        int topStart = Math.max(2, Math.round(height * 0.03f));
        int topEnd = Math.max(topStart + 1, Math.round(height * 0.45f));
        int bottomStart = Math.min(height - 3, Math.round(height * 0.55f));
        int bottomEnd = Math.min(height - 2, Math.round(height * 0.97f));

        int left = peakIndex(columnScore, leftStart, leftEnd);
        int right = peakIndex(columnScore, rightStart, rightEnd);
        int top = peakIndex(rowScore, topStart, topEnd);
        int bottom = peakIndex(rowScore, bottomStart, bottomEnd);

        if (right <= left || bottom <= top) return null;

        float detectedWidth = (right - left) / (float) width;
        float detectedHeight = (bottom - top) / (float) height;
        if (detectedWidth < 0.36f || detectedHeight < 0.36f) return null;

        double leftRatio = peakToMean(columnScore, leftStart, leftEnd, left);
        double rightRatio = peakToMean(columnScore, rightStart, rightEnd, right);
        double topRatio = peakToMean(rowScore, topStart, topEnd, top);
        double bottomRatio = peakToMean(rowScore, bottomStart, bottomEnd, bottom);
        double weakest = Math.min(Math.min(leftRatio, rightRatio), Math.min(topRatio, bottomRatio));

        if (weakest < 1.15d) return null;

        float padX = width * 0.015f;
        float padY = height * 0.015f;

        float normalizedLeft = clamp01((left - padX) / width);
        float normalizedTop = clamp01((top - padY) / height);
        float normalizedRight = clamp01((right + padX) / width);
        float normalizedBottom = clamp01((bottom + padY) / height);

        if (normalizedRight - normalizedLeft < 0.35f
                || normalizedBottom - normalizedTop < 0.35f) {
            return null;
        }

        int confidence = (int) Math.round(55d + Math.min(41d, (weakest - 1d) * 38d));
        confidence = Math.max(55, Math.min(96, confidence));

        float croppedWidthPx = (normalizedRight - normalizedLeft) * width;
        float croppedHeightPx = (normalizedBottom - normalizedTop) * height;
        float aspectRatio =
                Math.max(croppedWidthPx, croppedHeightPx)
                        / Math.max(1f, Math.min(croppedWidthPx, croppedHeightPx));

        return new CropProposal(
                normalizedLeft,
                normalizedTop,
                normalizedRight,
                normalizedBottom,
                confidence,
                aspectRatio);
    }

    private static double[] smooth(double[] values, int radius) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            int start = Math.max(0, i - radius);
            int end = Math.min(values.length - 1, i + radius);
            double sum = 0d;
            for (int j = start; j <= end; j++) {
                sum += values[j];
            }
            result[i] = sum / Math.max(1, end - start + 1);
        }
        return result;
    }

    private static int peakIndex(double[] values, int start, int end) {
        int best = start;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, start); i <= Math.min(values.length - 1, end); i++) {
            if (values[i] > bestValue) {
                bestValue = values[i];
                best = i;
            }
        }
        return best;
    }

    private static double peakToMean(double[] values, int start, int end, int peak) {
        double sum = 0d;
        int count = 0;
        for (int i = Math.max(0, start); i <= Math.min(values.length - 1, end); i++) {
            sum += values[i];
            count++;
        }
        double mean = sum / Math.max(1, count);
        if (mean <= 0d) return 0d;
        return values[peak] / mean;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
