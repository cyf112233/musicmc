import javax.sound.sampled.*;

public class AudioProbe {
    public static void main(String[] args) {
        System.out.println("== java.version=" + System.getProperty("java.version"));
        System.out.println("== os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        try {
            Mixer.Info[] infos = AudioSystem.getMixerInfo();
            System.out.println("== mixer count=" + infos.length);
            for (Mixer.Info i : infos) {
                System.out.println("   mixer: " + i.getName() + " | " + i.getVendor() + " | " + i.getDescription());
            }
        } catch (Throwable t) {
            System.out.println("mixer enum FAIL: " + t);
        }
        int[][] cfgs = {{48000,2},{44100,2},{44100,1},{48000,1},{96000,2}};
        for (int[] cfg : cfgs) {
            int rate = cfg[0], ch = cfg[1];
            AudioFormat fmt = new AudioFormat(rate, 16, ch, true, false);
            probe(fmt, "open(default)");
            probe(fmt, "open(65536)");
        }
    }

    static void probe(AudioFormat fmt, String mode) {
        String tag = fmt.getSampleRate() + "/" + fmt.getChannels() + "ch " + mode;
        try {
            SourceDataLine dl = AudioSystem.getSourceDataLine(fmt);
            DataLine.Info inf = (DataLine.Info) dl.getLineInfo();
            System.out.println("[" + tag + "] getSourceDataLine OK. minBuf=" + inf.getMinBufferSize() + " maxBuf=" + inf.getMaxBufferSize());
            try {
                if (mode.contains("65536")) dl.open(fmt, 65536);
                else dl.open(fmt);
                System.out.println("[" + tag + "] open OK. getBufferSize=" + dl.getBufferSize() + " frameSize=" + dl.getFormat().getFrameSize());
                dl.start();
                System.out.println("[" + tag + "] started. controls:");
                for (Control c : dl.getControls()) {
                    System.out.println("      control: " + c.getType() + " class=" + c.getClass().getSimpleName()
                        + (c instanceof FloatControl ? " min=" + ((FloatControl)c).getMinimum() + " max=" + ((FloatControl)c).getMaximum() : ""));
                }
                testGain(dl, tag, FloatControl.Type.MASTER_GAIN, -9.0f);
                testGain(dl, tag, FloatControl.Type.VOLUME, 0.35f);
                // write silence 1s, measure blocking time
                byte[] zeros = new byte[fmt.getFrameSize() * (int) fmt.getSampleRate()]; // 1 second
                long t0 = System.nanoTime();
                dl.write(zeros, 0, zeros.length);
                long t1 = System.nanoTime();
                System.out.println("[" + tag + "] write 1s silence took " + ((t1 - t0) / 1_000_000) + " ms (first write, may include open latency)");
                dl.drain();
                dl.stop();
                dl.close();
            } catch (LineUnavailableException e) {
                System.out.println("[" + tag + "] open FAILED: " + e + " -> " + (e.getCause() != null ? e.getCause() : ""));
            }
        } catch (IllegalArgumentException e) {
            System.out.println("[" + tag + "] getSourceDataLine FAILED: " + e.getMessage());
        } catch (Throwable t) {
            System.out.println("[" + tag + "] EXCEPTION: " + t);
        }
    }

    static void testGain(SourceDataLine dl, String tag, FloatControl.Type type, float value) {
        String name = type.toString();
        try {
            FloatControl fc = (FloatControl) dl.getControl(type);
            System.out.println("      " + name + " FOUND: min=" + fc.getMinimum() + " max=" + fc.getMaximum()
                + " prec=" + fc.getPrecision() + " now=" + fc.getValue());
            fc.setValue(value);
            System.out.println("      " + name + " setValue(" + value + ") -> readback=" + fc.getValue() + " (lineActive=" + dl.isActive() + ")");
        } catch (IllegalArgumentException e) {
            System.out.println("      " + name + " NOT SUPPORTED (IllegalArgumentException)");
        } catch (Throwable t) {
            System.out.println("      " + name + " setValue threw: " + t);
        }
    }
}