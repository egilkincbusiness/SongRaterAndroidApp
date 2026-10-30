package com.songrater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Minimal ID3v2 reader/rewriter. It only understands enough to:
 *  - read artist / album / title and the rating (POPM frame), and
 *  - copy a file while setting the rating, leaving every other frame byte-for-byte intact.
 * The ID3v1 tag and the audio data are copied through untouched.
 * Pure Java, no Android dependencies.
 */
public final class Id3Tag {
    private Id3Tag() {}

    private static final int MAX_TAG_BYTES = 64 * 1024 * 1024;

    // ---- public API -------------------------------------------------------------------------

    public static final class Info {
        public String artist = "";
        public String album = "";
        public String title = "";
        /** Raw rating byte of the first POPM frame, 0 if there is none. */
        public int popm = 0;

        public int stars() {
            return starsFromPopm(popm);
        }
    }

    public static final class CopyResult {
        public final long read;
        public final long written;

        CopyResult(long read, long written) {
            this.read = read;
            this.written = written;
        }
    }

    /** Raw POPM byte -> 0..5 stars. Uses the common banding so odd values written by other tools still map sensibly. */
    public static int starsFromPopm(int raw) {
        if (raw <= 0) return 0;
        if (raw <= 31) return 1;
        if (raw <= 95) return 2;
        if (raw <= 159) return 3;
        if (raw <= 223) return 4;
        return 5;
    }

    /** Stars -> the standard POPM byte (1, 64, 128, 196, 255). */
    public static int popmFromStars(int stars) {
        switch (stars) {
            case 1: return 1;
            case 2: return 64;
            case 3: return 128;
            case 4: return 196;
            case 5: return 255;
            default: throw new IllegalArgumentException("stars must be 1..5");
        }
    }

    /** Reads display info from the start of an MP3 stream. Only consumes the ID3v2 tag. */
    public static Info read(InputStream in) throws IOException {
        Info info = new Info();
        byte[] head = new byte[10];
        int n = readUpTo(in, head, 0, 10);
        if (n < 10 || !isId3(head)) return info;
        Tag tag = parse(in, head);
        boolean gotRating = false;
        for (Frame f : tag.frames) {
            switch (f.id) {
                case "TPE1": if (info.artist.isEmpty()) info.artist = decodeText(f.data); break;
                case "TALB": if (info.album.isEmpty()) info.album = decodeText(f.data); break;
                case "TIT2": if (info.title.isEmpty()) info.title = decodeText(f.data); break;
                case "POPM":
                    if (!gotRating) {
                        int z = indexOfZero(f.data);
                        if (z >= 0 && z + 1 < f.data.length) {
                            info.popm = f.data[z + 1] & 0xFF;
                            gotRating = true;
                        }
                    }
                    break;
                default: break;
            }
        }
        return info;
    }

    /**
     * Copies an MP3 from {@code in} to {@code out}, writing a new ID3v2 tag whose POPM rating is set
     * to {@code stars}. All other frames are preserved; the audio and any ID3v1 tag are copied verbatim.
     * {@code in} must be positioned at the start of the file.
     */
    public static CopyResult copyWithRating(InputStream in, OutputStream out, int stars) throws IOException {
        int popm = popmFromStars(stars);
        byte[] head = new byte[10];
        int n = readUpTo(in, head, 0, 10);
        long read = n;

        Tag tag;
        byte[] leftover = null;
        if (n == 10 && isId3(head)) {
            tag = parse(in, head);
            read = tag.totalLength;
        } else {
            tag = new Tag();
            tag.version = 3;
            leftover = Arrays.copyOf(head, n);
        }

        applyRating(tag, popm);
        byte[] newTag = build(tag);
        out.write(newTag);
        long written = newTag.length;

        if (leftover != null && leftover.length > 0) {
            out.write(leftover);
            written += leftover.length;
        }

        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
            written += r;
            read += r;
        }
        out.flush();
        return new CopyResult(read, written);
    }

    // ---- internals --------------------------------------------------------------------------

    private static final class Frame {
        String id;
        int flags;
        byte[] data;
    }

    private static final class Tag {
        int version = 3;               // version we write: 3 or 4
        List<Frame> frames = new ArrayList<>();
        long totalLength = 0;          // bytes the old tag occupied in the file (header + body + footer)
    }

    private static boolean isId3(byte[] h) {
        return h[0] == 'I' && h[1] == 'D' && h[2] == '3'
                && (h[3] & 0xFF) != 0xFF && (h[4] & 0xFF) != 0xFF
                && (h[6] & 0x80) == 0 && (h[7] & 0x80) == 0 && (h[8] & 0x80) == 0 && (h[9] & 0x80) == 0;
    }

    private static Tag parse(InputStream in, byte[] head) throws IOException {
        int major = head[3] & 0xFF;
        int flags = head[5] & 0xFF;
        if (major < 2 || major > 4) throw new IOException("Unsupported ID3v2 version 2." + major);
        if (major == 2 && (flags & 0x40) != 0) throw new IOException("Compressed ID3v2.2 tags are not supported");
        int size = syncsafe(head, 6);
        if (size > MAX_TAG_BYTES) throw new IOException("ID3v2 tag is unreasonably large");

        byte[] body = new byte[size];
        if (readUpTo(in, body, 0, size) < size) throw new IOException("Truncated ID3v2 tag");
        long total = 10L + size;
        if (major == 4 && (flags & 0x10) != 0) {            // footer
            byte[] footer = new byte[10];
            total += readUpTo(in, footer, 0, 10);
        }

        boolean unsyncAll = (flags & 0x80) != 0;
        if (major == 3 && unsyncAll) body = deunsync(body);

        int pos = 0;
        if (major >= 3 && (flags & 0x40) != 0) {            // extended header: skip it
            if (body.length < 4) throw new IOException("Corrupt ID3v2 extended header");
            pos = (major == 3) ? 4 + int32(body, 0) : syncsafe(body, 0);
            if (pos < 0 || pos > body.length) throw new IOException("Corrupt ID3v2 extended header");
        }

        Tag tag = new Tag();
        tag.totalLength = total;
        tag.version = (major == 4) ? 4 : 3;

        int hdr = (major == 2) ? 6 : 10;
        while (pos + hdr <= body.length) {
            if (body[pos] == 0) break;                      // padding
            String id;
            int fsize;
            int fflags = 0;
            if (major == 2) {
                id = ascii(body, pos, 3);
                fsize = ((body[pos + 3] & 0xFF) << 16) | ((body[pos + 4] & 0xFF) << 8) | (body[pos + 5] & 0xFF);
            } else {
                id = ascii(body, pos, 4);
                if (major == 4) {
                    // Some old writers (iTunes) used plain integers in 2.4; fall back if not syncsafe.
                    fsize = allSyncsafe(body, pos + 4) ? syncsafe(body, pos + 4) : int32(body, pos + 4);
                } else {
                    fsize = int32(body, pos + 4);
                }
                fflags = ((body[pos + 8] & 0xFF) << 8) | (body[pos + 9] & 0xFF);
            }
            if (!validId(id)) break;                        // junk: treat the rest as padding
            if (fsize < 0 || (long) pos + hdr + fsize > body.length) throw new IOException("Corrupt ID3v2 frame " + id);
            byte[] data = Arrays.copyOfRange(body, pos + hdr, pos + hdr + fsize);
            pos += hdr + fsize;

            if (major == 2) {
                String mapped = V22.get(id);
                if (mapped == null) continue;               // unknown 2.2 frame: cannot carry over
                if (id.equals("PIC")) {
                    data = convertPic(data);
                    if (data == null) continue;
                }
                id = mapped;
            } else if (major == 4 && unsyncAll) {
                data = deunsync(data);
                fflags &= ~0x0002;
            }

            Frame f = new Frame();
            f.id = id;
            f.flags = fflags;
            f.data = data;
            tag.frames.add(f);
        }
        return tag;
    }

    private static void applyRating(Tag tag, int popm) {
        int badFlags = (tag.version == 4) ? 0x000F : 0x00C0;   // compression / encryption / unsync / length indicator
        boolean found = false;
        Iterator<Frame> it = tag.frames.iterator();
        while (it.hasNext()) {
            Frame f = it.next();
            if (!f.id.equals("POPM")) continue;
            int z = indexOfZero(f.data);
            if ((f.flags & badFlags) != 0 || z < 0 || z + 1 >= f.data.length) {
                it.remove();                                // unusable frame: replaced below
                continue;
            }
            f.data[z + 1] = (byte) popm;                    // keep email and counter, update every POPM frame
            found = true;
        }
        if (!found) {
            Frame f = new Frame();
            f.id = "POPM";
            f.flags = 0;
            f.data = new byte[] {0, (byte) popm, 0, 0, 0, 0};   // empty email, rating, 4-byte counter
            tag.frames.add(f);
        }
    }

    private static byte[] build(Tag t) throws IOException {
        long bodyLen = 0;
        for (Frame f : t.frames) bodyLen += 10L + f.data.length;
        if (bodyLen > 0x0FFFFFFFL) throw new IOException("ID3v2 tag too large");
        byte[] out = new byte[(int) (10 + bodyLen)];
        out[0] = 'I'; out[1] = 'D'; out[2] = '3';
        out[3] = (byte) t.version;
        putSyncsafe(out, 6, (int) bodyLen);
        int p = 10;
        for (Frame f : t.frames) {
            byte[] idb = f.id.getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(idb, 0, out, p, 4);
            if (t.version == 4) putSyncsafe(out, p + 4, f.data.length); else putInt32(out, p + 4, f.data.length);
            out[p + 8] = (byte) (f.flags >> 8);
            out[p + 9] = (byte) f.flags;
            System.arraycopy(f.data, 0, out, p + 10, f.data.length);
            p += 10 + f.data.length;
        }
        return out;
    }

    private static String decodeText(byte[] d) {
        if (d.length < 2) return "";
        Charset cs;
        switch (d[0] & 0xFF) {
            case 1: cs = StandardCharsets.UTF_16; break;
            case 2: cs = StandardCharsets.UTF_16BE; break;
            case 3: cs = StandardCharsets.UTF_8; break;
            default: cs = StandardCharsets.ISO_8859_1; break;
        }
        String s = new String(d, 1, d.length - 1, cs).replace("﻿", "").replace("￾", "");
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("\u0000")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(p);
        }
        return sb.toString();
    }

    /** ID3v2.2 PIC -> ID3v2.3 APIC (text enc, 3-char format, type, desc, data -> enc, MIME\0, type, desc, data). */
    private static byte[] convertPic(byte[] d) {
        if (d.length < 5) return null;
        String fmt = ascii(d, 1, 3).toUpperCase();
        String mime = fmt.equals("JPG") ? "image/jpeg" : fmt.equals("PNG") ? "image/png" : "image/" + fmt.toLowerCase();
        byte[] m = mime.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[1 + m.length + 1 + (d.length - 4)];
        out[0] = d[0];
        System.arraycopy(m, 0, out, 1, m.length);
        out[1 + m.length] = 0;
        System.arraycopy(d, 4, out, 2 + m.length, d.length - 4);
        return out;
    }

    private static final Map<String, String> V22 = new HashMap<>();
    static {
        String[] pairs = {
            "TT1","TIT1","TT2","TIT2","TT3","TIT3","TP1","TPE1","TP2","TPE2","TP3","TPE3","TP4","TPE4",
            "TAL","TALB","TRK","TRCK","TYE","TYER","TDA","TDAT","TIM","TIME","TCO","TCON","TCM","TCOM",
            "TEN","TENC","TLA","TLAN","TLE","TLEN","TPA","TPOS","TPB","TPUB","TXT","TEXT","TXX","TXXX",
            "TCR","TCOP","TKE","TKEY","TOA","TOPE","TOT","TOAL","TOL","TOLY","TOR","TORY","TRC","TSRC",
            "TSS","TSSE","TBP","TBPM","TFT","TFLT","TMT","TMED","TDY","TDLY","TSI","TSIZ","TRD","TRDA",
            "TCP","TCMP","WAF","WOAF","WAR","WOAR","WAS","WOAS","WCM","WCOM","WCP","WCOP","WPB","WPUB",
            "WXX","WXXX","COM","COMM","ULT","USLT","UFI","UFID","IPL","IPLS","MCI","MCDI","ETC","ETCO",
            "MLL","MLLT","STC","SYTC","SLT","SYLT","RVA","RVAD","EQU","EQUA","REV","RVRB","GEO","GEOB",
            "CNT","PCNT","POP","POPM","BUF","RBUF","CRA","AENC","LNK","LINK","PIC","APIC"
        };
        for (int i = 0; i < pairs.length; i += 2) V22.put(pairs[i], pairs[i + 1]);
    }

    // ---- byte helpers -----------------------------------------------------------------------

    private static int readUpTo(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(buf, off + total, len - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static int indexOfZero(byte[] d) {
        for (int i = 0; i < d.length; i++) if (d[i] == 0) return i;
        return -1;
    }

    private static boolean validId(String id) {
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.ISO_8859_1);
    }

    private static boolean allSyncsafe(byte[] b, int o) {
        return (b[o] & 0x80) == 0 && (b[o + 1] & 0x80) == 0 && (b[o + 2] & 0x80) == 0 && (b[o + 3] & 0x80) == 0;
    }

    private static int syncsafe(byte[] b, int o) {
        return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14) | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
    }

    private static int int32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static void putSyncsafe(byte[] b, int o, int v) {
        b[o] = (byte) ((v >> 21) & 0x7F);
        b[o + 1] = (byte) ((v >> 14) & 0x7F);
        b[o + 2] = (byte) ((v >> 7) & 0x7F);
        b[o + 3] = (byte) (v & 0x7F);
    }

    private static void putInt32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    private static byte[] deunsync(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        for (int i = 0; i < d.length; i++) {
            out.write(d[i]);
            if ((d[i] & 0xFF) == 0xFF && i + 1 < d.length && d[i + 1] == 0) i++;
        }
        return out.toByteArray();
    }
}
