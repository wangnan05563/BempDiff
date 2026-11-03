import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.zip.*;

/**
 * B5 收益量化微基准（单线程，Soak 结束后在无争用机器上跑）。
 *
 * 真实路径：PackageParser.sha256Stream 在**每个 zip 条目**上调用一次，
 * 每次既 MessageDigest.getInstance("SHA-256") 又 new byte[8192]
 * （见 PackageParser.java:594-621）。所以耗时可能来自三处，必须拆开才能谈收益：
 *   A. 流缓冲大小对「读 + 摘要」吞吐的影响；
 *   B. 条目级 API 开销（getInstance + 缓冲分配 × 条目数）；
 *   C. zip 遍历/解压本身（不摘要）作为地板值。
 */
public class HashProbe {

    /** 每次调用新建 digest + 新建缓冲（等价现网 :611 写法）。 */
    static String freshPerEntry(InputStream in, byte[] unusedShared, boolean reuseBuf,
                               MessageDigest proto) throws Exception {
        MessageDigest md;
        byte[] buf;
        if (proto != null) {
            md = (MessageDigest) proto.clone();
        } else {
            md = MessageDigest.getInstance("SHA-256");
        }
        if (reuseBuf && unusedShared != null) {
            buf = unusedShared;
        } else {
            buf = new byte[8192];
        }
        int r;
        while ((r = in.read(buf)) != -1) {
            md.update(buf, 0, r);
        }
        return hex(md.digest());
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return s.toString();
    }

    /** A：整文件读 + SHA-256，指定缓冲。 */
    static long wholeFile(Path p, int bufSize, boolean withHash) throws Exception {
        MessageDigest md = withHash ? MessageDigest.getInstance("SHA-256") : null;
        byte[] b = new byte[bufSize];
        long t0 = System.nanoTime();
        long got = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p), bufSize)) {
            int n;
            while ((n = in.read(b)) > 0) {
                got += n;
                if (md != null) {
                    md.update(b, 0, n);
                }
            }
        }
        if (md != null) {
            md.digest();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (got < 1) {
            throw new IOException("no bytes");
        }
        return ms;
    }

    /** B/C：按真实条目遍历 fixture，variant 控制条目级开销。 */
    static long perEntry(Path zip, String variant, int bufSize) throws Exception {
        MessageDigest proto = "clone".equals(variant) ? MessageDigest.getInstance("SHA-256") : null;
        byte[] shared = ("bufreused".equals(variant) || "clone".equals(variant))
                ? new byte[bufSize] : null;
        boolean reuse = shared != null;
        boolean hash = !"traverse".equals(variant);
        long t0 = System.nanoTime();
        long entries = 0;
        long bytes = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                entries++;
                try (InputStream in = zf.getInputStream(e)) {
                    byte[] b = reuse ? shared : (hash ? null : new byte[bufSize]);
                    if (!hash) {
                        while (in.read(b) != -1) {
                            bytes += b.length;
                        }
                    } else if (b != null) {
                        int r;
                        MessageDigest md = proto != null ? (MessageDigest) proto.clone()
                                : MessageDigest.getInstance("SHA-256");
                        while ((r = in.read(b)) != -1) {
                            bytes += r;
                            md.update(b, 0, r);
                        }
                        md.digest();
                    } else {
                        String h = freshPerEntry(in, null, false, proto);
                        bytes += h.length();
                    }
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("        entries=%d bytes=%d%n", entries, bytes);
        return ms;
    }

    public static void main(String[] a) throws Exception {
        Path file = Paths.get(a[0]);
        Path zip = a.length > 1 ? Paths.get(a[1]) : file;
        System.out.println("== A. 整文件 读+SHA-256 vs 只读（buf 扫描）");
        for (int r = 0; r < 3; r++) {
            for (int buf : new int[] {8192, 65536, 262144}) {
                System.out.printf("round=%d buf=%6d read+sha=%6dms read-only=%6dms%n",
                        r, buf, wholeFile(file, buf, true), wholeFile(file, buf, false));
            }
        }
        System.out.println("== B. 条目级：现网写法（每条目 getInstance + new byte[8192]）");
        for (int r = 0; r < 3; r++) {
            System.out.printf("round=%d variant=fresh      %6dms%n", r, perEntry(zip, "fresh", 8192));
            System.out.printf("round=%d variant=bufreused  %6dms%n", r, perEntry(zip, "bufreused", 8192));
            System.out.printf("round=%d variant=clone      %6dms%n", r, perEntry(zip, "clone", 8192));
            System.out.printf("round=%d variant=traverse   %6dms%n", r, perEntry(zip, "traverse", 8192));
        }
    }
}
