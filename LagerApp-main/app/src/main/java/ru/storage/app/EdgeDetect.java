package ru.storage.app;

import java.util.Random;

/**
 * Поиск четырёх краёв листа бумаги на фото (без OpenCV, только массив пикселей).
 *
 * Идея: вместо «угла по самой дальней точке белого пятна» каждая из четырёх сторон листа находится как прямая
 * по надёжным участкам границы (RANSAC). Зона у степлера (левый верхний угол), где снизу выглядывают другие листы стопки,
 * при подборе верхней и левой прямых не учитывается. Угол листа — пересечение двух прямых.
 */
public final class EdgeDetect {
    private EdgeDetect() {}

    /**
     * @param px ARGB-пиксели изображения (рекомендуется 480–640 пикселей по длинной стороне)
     * @param stapleTopLeft true — степлер в левом верхнем углу: возле этого угла границе не верим сильнее
     * @return 8 чисел x,y углов в порядке: левый верхний, правый верхний, правый нижний, левый нижний; либо null, если лист не найден
     */
    public static float[] findQuad(int[] px, int w, int h, boolean stapleTopLeft) {
        if (px == null || w < 32 || h < 32 || px.length < w * h) return null;
        int n = w * h;

        // 1) «белизна»: минимум из R, G, B (цветной стол даёт низкое значение, бумага — высокое)
        int[] g = new int[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
            g[i] = Math.min(r, Math.min(gg, b));
        }
        g = blur(g, w, h);

        // 2) порог по Оцу
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) hist[g[i]]++;
        long sumAll = 0;
        for (int t = 0; t < 256; t++) sumAll += (long) t * hist[t];
        long wB = 0, sumB = 0;
        double best = -1;
        int thr = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            long wF = n - wB;
            if (wF == 0) break;
            sumB += (long) t * hist[t];
            double mB = (double) sumB / wB;
            double mF = (double) (sumAll - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                thr = t;
            }
        }

        // 3) самая большая светлая область — это лист (вместе с выглядывающими краями стопки)
        boolean[] comp = largestBright(g, w, h, thr);
        if (comp == null) return null;

        int size = 0;
        int[] cx = new int[4];
        int[] cy = new int[4];
        int minSum = Integer.MAX_VALUE, maxSum = Integer.MIN_VALUE, minDif = Integer.MAX_VALUE, maxDif = Integer.MIN_VALUE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!comp[y * w + x]) continue;
                size++;
                int s = x + y, d = x - y;
                if (s < minSum) { minSum = s; cx[0] = x; cy[0] = y; }          // левый верхний
                if (d > maxDif) { maxDif = d; cx[1] = x; cy[1] = y; }          // правый верхний
                if (s > maxSum) { maxSum = s; cx[2] = x; cy[2] = y; }          // правый нижний
                if (d < minDif) { minDif = d; cx[3] = x; cy[3] = y; }          // левый нижний
            }
        }
        if (size < 0.12 * n || size > 0.985 * n) return null;

        // 4) пиксели внешней границы области (дырки от текста внутри листа не считаются)
        boolean[] outside = new boolean[n];
        int[] oq = new int[n];
        int oh = 0, ot = 0;
        for (int x = 0; x < w; x++) {
            int top = x, bot = (h - 1) * w + x;
            if (!comp[top] && !outside[top]) { outside[top] = true; oq[ot++] = top; }
            if (!comp[bot] && !outside[bot]) { outside[bot] = true; oq[ot++] = bot; }
        }
        for (int y = 0; y < h; y++) {
            int l = y * w, rgt = y * w + w - 1;
            if (!comp[l] && !outside[l]) { outside[l] = true; oq[ot++] = l; }
            if (!comp[rgt] && !outside[rgt]) { outside[rgt] = true; oq[ot++] = rgt; }
        }
        while (oh < ot) {
            int p = oq[oh++];
            int x = p % w;
            if (x > 0 && !comp[p - 1] && !outside[p - 1]) { outside[p - 1] = true; oq[ot++] = p - 1; }
            if (x < w - 1 && !comp[p + 1] && !outside[p + 1]) { outside[p + 1] = true; oq[ot++] = p + 1; }
            if (p >= w && !comp[p - w] && !outside[p - w]) { outside[p - w] = true; oq[ot++] = p - w; }
            if (p < n - w && !comp[p + w] && !outside[p + w]) { outside[p + w] = true; oq[ot++] = p + w; }
        }
        int bc = 0;
        int[] bx = new int[Math.min(n, 4 * (w + h) * 4)];
        int[] by = new int[bx.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!comp[i]) continue;
                boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1
                        || outside[i - 1] || outside[i + 1] || outside[i - w] || outside[i + w];
                if (edge && bc < bx.length) {
                    bx[bc] = x;
                    by[bc] = y;
                    bc++;
                }
            }
        }
        if (bc < 80) return null;

        // 5) точки границы раскладываем по четырём сторонам (грубые стороны — по крайним точкам), края сторон отбрасываем
        double tol = 0.06 * Math.max(w, h);
        double[] lo = {stapleTopLeft ? 0.35 : 0.15, 0.15, 0.15, 0.15};
        double[] hi = {0.85, 0.85, 0.85, stapleTopLeft ? 0.65 : 0.85};
        int[] cnt = new int[4];
        float[][] sx = new float[4][bc];
        float[][] sy = new float[4][bc];
        for (int k = 0; k < bc; k++) {
            double px0 = bx[k], py0 = by[k];
            int bestSide = -1;
            double bestD = Double.MAX_VALUE, bestT = 0;
            for (int s = 0; s < 4; s++) {
                int a = s, b = (s + 1) & 3;
                double dx = cx[b] - cx[a], dy = cy[b] - cy[a];
                double len2 = dx * dx + dy * dy;
                if (len2 < 100) return null;
                double len = Math.sqrt(len2);
                double t = ((px0 - cx[a]) * dx + (py0 - cy[a]) * dy) / len2;
                double d = Math.abs((px0 - cx[a]) * dy - (py0 - cy[a]) * dx) / len;
                if (t < -0.02 || t > 1.02 || d > tol) continue;
                if (d < bestD) { bestD = d; bestSide = s; bestT = t; }
            }
            if (bestSide < 0) continue;
            if (bestT < lo[bestSide] || bestT > hi[bestSide]) continue;
            sx[bestSide][cnt[bestSide]] = (float) px0;
            sy[bestSide][cnt[bestSide]] = (float) py0;
            cnt[bestSide]++;
        }

        // 6) прямая для каждой стороны
        Random rnd = new Random(12345);
        double[][] line = new double[4][];
        for (int s = 0; s < 4; s++) {
            int m = cnt[s];
            int step = Math.max(1, m / 1500);
            int mm = 0;
            float[] ax = new float[m / step + 1];
            float[] ay = new float[m / step + 1];
            for (int i = 0; i < m; i += step) { ax[mm] = sx[s][i]; ay[mm] = sy[s][i]; mm++; }
            line[s] = fitLine(ax, ay, mm, rnd, 1.8);
            if (line[s] == null) return null;
        }

        // 7) углы — пересечения соседних прямых: top∩left, top∩right, bottom∩right, bottom∩left
        double[] tl = cross(line[0], line[3]);
        double[] tr = cross(line[0], line[1]);
        double[] br = cross(line[2], line[1]);
        double[] bl = cross(line[2], line[3]);
        if (tl == null || tr == null || br == null || bl == null) return null;
        float[] q = {(float) tl[0], (float) tl[1], (float) tr[0], (float) tr[1],
                (float) br[0], (float) br[1], (float) bl[0], (float) bl[1]};
        return valid(q, w, h) ? q : null;
    }

    private static boolean valid(float[] q, int w, int h) {
        for (int i = 0; i < 4; i++) {
            if (Float.isNaN(q[2 * i]) || Float.isNaN(q[2 * i + 1])) return false;
            if (q[2 * i] < -0.15f * w || q[2 * i] > 1.15f * w) return false;
            if (q[2 * i + 1] < -0.15f * h || q[2 * i + 1] > 1.15f * h) return false;
        }
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3, k = (i + 2) & 3;
            double c = (q[2 * j] - q[2 * i]) * (q[2 * k + 1] - q[2 * j + 1])
                    - (q[2 * j + 1] - q[2 * i + 1]) * (q[2 * k] - q[2 * j]);
            if (c <= 0) return false;
            area += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1];
        }
        if (Math.abs(area) / 2.0 < 0.12 * w * h) return false;
        double top = dist(q, 0, 1), bottom = dist(q, 3, 2), left = dist(q, 0, 3), right = dist(q, 1, 2);
        if (top < 20 || bottom < 20 || left < 20 || right < 20) return false;
        double rw = top / bottom, rh = left / right;
        return rw > 0.5 && rw < 2.0 && rh > 0.5 && rh < 2.0;
    }

    private static double dist(float[] q, int a, int b) {
        return Math.hypot(q[2 * a] - q[2 * b], q[2 * a + 1] - q[2 * b + 1]);
    }

    private static double[] cross(double[] l1, double[] l2) {
        double det = l1[0] * l2[1] - l2[0] * l1[1];
        if (Math.abs(det) < 0.25) return null; // соседние стороны листа почти параллельны — так не бывает
        double x = (l1[1] * l2[2] - l2[1] * l1[2]) / det;
        double y = (l1[2] * l2[0] - l2[2] * l1[0]) / det;
        return new double[]{x, y};
    }

    /** Прямая a*x + b*y + c = 0 (a²+b²=1) по точкам: RANSAC, затем уточнение по найденным точкам. */
    private static double[] fitLine(float[] xs, float[] ys, int n, Random rnd, double tol) {
        if (n < 20) return null;
        int bestCnt = -1;
        double ba = 0, bb = 0, bcc = 0;
        for (int it = 0; it < 200; it++) {
            int i = rnd.nextInt(n), j = rnd.nextInt(n);
            if (i == j) continue;
            double dx = xs[j] - xs[i], dy = ys[j] - ys[i];
            double len = Math.hypot(dx, dy);
            if (len < 12) continue;
            double a = dy / len, b = -dx / len, c = -(a * xs[i] + b * ys[i]);
            int cnt = 0;
            for (int k = 0; k < n; k++) if (Math.abs(a * xs[k] + b * ys[k] + c) <= tol) cnt++;
            if (cnt > bestCnt) { bestCnt = cnt; ba = a; bb = b; bcc = c; }
        }
        if (bestCnt < Math.max(15, n * 0.4)) return null;
        double a = ba, b = bb, c = bcc;
        for (int r = 0; r < 2; r++) {
            double sx = 0, sy = 0;
            int m = 0;
            for (int k = 0; k < n; k++) {
                if (Math.abs(a * xs[k] + b * ys[k] + c) <= tol * 1.5) { sx += xs[k]; sy += ys[k]; m++; }
            }
            if (m < 10) return null;
            double mx = sx / m, my = sy / m;
            double sxx = 0, sxy = 0, syy = 0;
            for (int k = 0; k < n; k++) {
                if (Math.abs(a * xs[k] + b * ys[k] + c) <= tol * 1.5) {
                    double ddx = xs[k] - mx, ddy = ys[k] - my;
                    sxx += ddx * ddx; sxy += ddx * ddy; syy += ddy * ddy;
                }
            }
            double th = 0.5 * Math.atan2(2 * sxy, sxx - syy);
            double dx = Math.cos(th), dy = Math.sin(th);
            a = dy; b = -dx; c = -(a * mx + b * my);
        }
        return new double[]{a, b, c};
    }

    private static int[] blur(int[] src, int w, int h) {
        int[] tmp = new int[src.length];
        int[] out = new int[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = src[y * w + Math.max(0, x - 1)], b = src[y * w + x], c = src[y * w + Math.min(w - 1, x + 1)];
                tmp[y * w + x] = (a + b + c) / 3;
            }
        }
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - 1), y1 = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++) {
                out[y * w + x] = (tmp[y0 * w + x] + tmp[y * w + x] + tmp[y1 * w + x]) / 3;
            }
        }
        return out;
    }

    /** Самая большая связная светлая область (g > thr), 4-связность. */
    private static boolean[] largestBright(int[] g, int w, int h, int thr) {
        int n = w * h;
        byte[] seen = new byte[n];
        int[] queue = new int[n];
        int bestSeed = -1, bestSize = 0;
        for (int s = 0; s < n; s++) {
            if (seen[s] != 0 || g[s] <= thr) continue;
            int head = 0, tail = 0;
            queue[tail++] = s;
            seen[s] = 1;
            while (head < tail) {
                int p = queue[head++];
                int x = p % w;
                if (x > 0 && seen[p - 1] == 0 && g[p - 1] > thr) { seen[p - 1] = 1; queue[tail++] = p - 1; }
                if (x < w - 1 && seen[p + 1] == 0 && g[p + 1] > thr) { seen[p + 1] = 1; queue[tail++] = p + 1; }
                if (p >= w && seen[p - w] == 0 && g[p - w] > thr) { seen[p - w] = 1; queue[tail++] = p - w; }
                if (p < n - w && seen[p + w] == 0 && g[p + w] > thr) { seen[p + w] = 1; queue[tail++] = p + w; }
            }
            if (tail > bestSize) { bestSize = tail; bestSeed = s; }
        }
        if (bestSeed < 0) return null;
        boolean[] comp = new boolean[n];
        int head = 0, tail = 0;
        queue[tail++] = bestSeed;
        comp[bestSeed] = true;
        while (head < tail) {
            int p = queue[head++];
            int x = p % w;
            if (x > 0 && !comp[p - 1] && g[p - 1] > thr) { comp[p - 1] = true; queue[tail++] = p - 1; }
            if (x < w - 1 && !comp[p + 1] && g[p + 1] > thr) { comp[p + 1] = true; queue[tail++] = p + 1; }
            if (p >= w && !comp[p - w] && g[p - w] > thr) { comp[p - w] = true; queue[tail++] = p - w; }
            if (p < n - w && !comp[p + w] && g[p + w] > thr) { comp[p + w] = true; queue[tail++] = p + w; }
        }
        return comp;
    }
}
