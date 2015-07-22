package free6om.research.qart4j;

import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder;
import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.Imaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

/**
 * Created by free6om on 7/21/15.
 */
public class Image {
    private static final Logger LOGGER = LoggerFactory.getLogger(Image.class);

    private int[][] target;
    private int dx, dy;
    private String URL;
    private int version;
    private int mask;
    private int quietZone;
    private int scale;
    private int rotation;

    private boolean randControl;
    private long seed;

    private boolean dither;

    private boolean onlyDataBits;

    private boolean saveControl;
    private byte[] control;

    public Image(String filename, int dx, int dy, String URL, int version, int mask, int quietZone, int scale, int rotation, int size, boolean randControl, long seed, boolean dither, boolean onlyDataBits, boolean saveControl) throws IOException, ImageReadException {
        this.dx = dx;
        this.dy = dy;
        this.URL = URL;
        this.version = version;
        this.mask = mask;
        this.quietZone = quietZone;
        this.scale = scale;
        this.rotation = rotation;
        this.randControl = randControl;
        this.seed = seed;
        this.dither = dither;
        this.onlyDataBits = onlyDataBits;
        this.saveControl = saveControl;
        this.target = makeTarget(filename, 17 + 4*version + size);
    }

    private int[][] makeTarget(String filename, int size) throws IOException, ImageReadException {
        BufferedImage image = loadImage(filename, size);
        int[][] target = new int[size][size];
        for (int y = 0; y < size; ++y) {
            for (int x = 0; x < size; ++x) {
                int argb = image.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                if (a == 0) {
                    target[y][x] = -1;
                } else {
                    target[y][x] = ((299 * r + 587 * g + 114 * b) + 500) / 1000;
                }
            }
        }

        return target;

    }

    private BufferedImage loadImage(String filename, int maxSize) throws IOException, ImageReadException {
        BufferedImage image = Imaging.getBufferedImage(new File(filename));
        int width = image.getWidth();
        int height = image.getHeight();

        int tmpWidth = maxSize;
        int tmpHeight = maxSize;
        if (width > height) {
            tmpHeight = height * width / maxSize;
        } else {
            tmpWidth = width * height / maxSize;
        }

        BufferedImage finalImage = new BufferedImage(tmpWidth, tmpHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics graphics = finalImage.createGraphics();
        graphics.drawImage(image, 0, 0, tmpWidth, tmpHeight, null);
        graphics.dispose();

        return finalImage;
    }

    public Target target(int x, int y) {
        int tx = x + dx;
        int ty = y + dy;
        if (ty < 0 || ty >= target.length || tx < 0 || tx >= target[ty].length) {
            return new Target((byte) 255, -1);
        }

        int v0 = target[ty][tx];
        if (v0 < 0) {
            return new Target((byte) 255, -1);
        }

        byte targ = (byte) v0;

        int n = 0;
        long sum = 0;
        long sumSequence = 0;
        int del = 5;
        for (int dy = -del; dy <= del; dy++) {
            for (int dx = -del; dx <= del; dx++) {
                if (0 <= ty+dy && ty+dy < this.target.length && 0 <= tx+dx && tx+dx < this.target[ty+dy].length) {
                    int v = this.target[ty+dy][tx+dx];
                    sum += v;
                    sumSequence += v * v;
                    n++;
                }
            }
        }

        int avg = (int) (sum / n);
        int contrast = (int) (sumSequence/n - avg*avg);
        return new Target(targ, contrast);
    }

    public void rotate(Plan plan, int rotation) {
        if(rotation == 0) {
            return;
        }

        int n = plan.getPixels().length;
        Pixel[][] pixels = new Pixel[n][n];

        switch (rotation) {
            case 1:
                for(int y = 0;y < n;y++) {
                    for(int x = 0;x < n;x++) {
                        pixels[y][x] = plan.getPixels()[x][n - 1 - y];
                    }
                }
                break;
            case 2:
                for(int y = 0;y < n;y++) {
                    for(int x = 0;x < n;x++) {
                        pixels[y][x] = plan.getPixels()[n - 1 - y][n - 1 - x];
                    }
                }
                break;
            case 3:
                for(int y = 0;y < n;y++) {
                    for(int x = 0;x < n;x++) {
                        pixels[y][x] = plan.getPixels()[n - 1 -x][y];
                    }
                }
                break;
        }

        plan.setPixels(pixels);
    }

    public BitMatrix encode() throws QArtException {
        Plan plan = Plan.newPlan(new Version(version), Level.L, new Mask(mask));

        rotate(plan, rotation);

        Random random = new Random(seed);

        // QR parameters.
        int numberOfDataBytesPerBlock = plan.getNumberOfDataBytes() / plan.getNumberOfBlocks();
        int numberOfCheckBytesPerBlock = plan.getNumberOfCheckBytes() / plan.getNumberOfBlocks();
        int numberOfExtraBytes = plan.getNumberOfDataBytes() - numberOfDataBytesPerBlock * plan.getNumberOfBlocks();
        ReedSolomonEncoder encoder = new ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256);

        // Build information about pixels, indexed by data/check bit number.
        PixelInfo[] pixelByOffset = new PixelInfo[(plan.getNumberOfDataBytes() + plan.getNumberOfCheckBytes()) * 8];
        boolean[][] expect = new boolean[plan.getPixels().length][plan.getPixels().length];
        Pixel[][] pixels = plan.getPixels();
        for(int y = 0;y < pixels.length;y++) {
            for(int x = 0;x < pixels[y].length;x++) {
                Pixel pixel = pixels[y][x];
                expect[y][x] = ((pixel.getPixelValue()&Pixel.BLACK.getPixelValue()) != 0);

                Target target = target(x, y);
                if(randControl && target.contrast >= 0) {
                    target.contrast = random.nextInt(128) + 64*((x+y)%2) + 64*((x+y)%3%2);
                }
                Pixel.PixelRole role = pixel.getPixelRole();
                if(role == Pixel.PixelRole.DATA || role == Pixel.PixelRole.CHECK) {
                    pixelByOffset[pixel.getOffset()] = new PixelInfo(x, y, pixel, target.target, target.contrast);
                }
            }
        }

        String url = this.URL + "#";
        Bits bits;
        byte[] numbers;
        int errorCount;

        do {
            bits = new Bits();
            // Count fixed initial data bits, prepare template URL.
            new Raw(url).encode(bits, plan.getVersion());
            new Number("").encode(bits, plan.getVersion());
            int headSize = bits.getSize();
            int dataBitsRemaining = plan.getNumberOfDataBytes()*8 - headSize;
            if(dataBitsRemaining < 0) {
                throw new QArtException("cannot encode URL into available bits");
            }

            numbers = new byte[dataBitsRemaining/10*3];
            Arrays.fill(numbers, (byte) '0');
            //todo delete pad?
            bits.pad(dataBitsRemaining);
            bits.reset();

            new Raw(url).encode(bits, plan.getVersion());
            new Number(new String(numbers)).encode(bits, plan.getVersion());
            bits.addCheckBytes(plan.getVersion(), plan.getLevel());

            byte[] data = bits.getBits();

            int dataOffset = 0;
            int checkOffset = 0;
            int mainDataBits = headSize + dataBitsRemaining/10*10;

            // Choose pixels.
            BitBlock[] bitBlocks = new BitBlock[plan.getNumberOfBlocks()];
            for (int blocknum = 0; blocknum < plan.getNumberOfBlocks(); blocknum++) {
                if (blocknum == plan.getNumberOfBlocks()-numberOfExtraBytes) {
                    numberOfDataBytesPerBlock++;
                }

                byte[] blockDataBytes = Arrays.copyOfRange(data, dataOffset/8, dataOffset/8 + numberOfDataBytesPerBlock);
//                byte[] blockCheckBytes = Arrays.copyOfRange(data,
//                        plan.getNumberOfDataBytes()+checkOffset/8,
//                        plan.getNumberOfDataBytes() + checkOffset/8 + numberOfCheckBytesPerBlock);
                BitBlock bitBlock = new BitBlock(numberOfDataBytesPerBlock, numberOfCheckBytesPerBlock, encoder,
                        data, dataOffset/8,
                        data, plan.getNumberOfDataBytes() + checkOffset/8);
                bitBlocks[blocknum] = bitBlock;

                // Determine which bits in this block we can try to edit.
                int low = 0, high = numberOfDataBytesPerBlock*8;
                if(low < headSize - dataOffset) {
                    low = headSize - dataOffset;
                    if(low > high) {
                        low = high;
                    }
                }
                if(high > mainDataBits - dataOffset) {
                    high = mainDataBits - dataOffset;
                    if(high < low) {
                        high = low;
                    }
                }


                // Preserve [0, lo) and [hi, nd*8).
                for (int i = 0; i < low; i++) {
                    if (!bitBlock.canSet(i, (byte) ((blockDataBytes[i/8]>>(7-i&7))&1))) {
                        throw new QArtException("cannot preserve required bits");
                    }
                }
                for (int i = high; i < numberOfDataBytesPerBlock*8; i++) {
                    if (!bitBlock.canSet(i, (byte) ((blockDataBytes[i/8]>>(7-i&7))&1))) {
                        throw new QArtException("cannot preserve required bits");
                    }
                }

                // Can edit [lo, hi) and checksum bits to hit target.
                // Determine which ones to try first.
                PixelOrder[] order = new PixelOrder[(high - low) + numberOfCheckBytesPerBlock*8];
                for(int i = 0;i < order.length;i++) {
                    order[i] = new PixelOrder();
                }
                for (int i = low; i < high; i++) {
                    order[i-low].setOffset(dataOffset + i);
                }
                for (int i = 0; i < numberOfCheckBytesPerBlock*8; i++) {
                    order[high-low+i].setOffset(plan.getNumberOfDataBytes()*8 + checkOffset + i);
                }
                if (onlyDataBits) {
                    order = Arrays.copyOf(order, high - low);
                }
                for (int i = 0;i < order.length;i++) {
                    PixelOrder pixelOrder = order[i];
                    pixelOrder.setPriority(pixelByOffset[pixelOrder.getOffset()].getContrast() << 8 | random.nextInt(256));
                }
                Arrays.sort(order, new Comparator<PixelOrder>() {
                    @Override
                    public int compare(PixelOrder o1, PixelOrder o2) {
                        return o2.getPriority() - o1.getPriority();
                    }
                });

                boolean mark = false;
                for (int i = 0;i < order.length;i++) {
                    PixelOrder po = order[i];
                    PixelInfo info = pixelByOffset[po.getOffset()];
                    //todo check value < 128
                    int value = ((int)info.getTarget())&0xFF;
                    if(value < 128) {
                        value = 1;
                    } else {
                        value = 0;
                    }

                    Pixel pixel = info.getPixel();
                    if((pixel.getPixelValue()&Pixel.INVERT.getPixelValue()) != 0) {
                        value ^= 1;
                    }
                    if(info.isHardZero()) {
                        value = 0;
                    }

                    int index;

                    if (pixel.getPixelRole() == Pixel.PixelRole.DATA) {
                        index = po.getOffset() - dataOffset;
                    } else {
                        index = po.getOffset() - plan.getNumberOfDataBytes()*8 - checkOffset + numberOfDataBytesPerBlock*8;
                    }
                    if (bitBlock.canSet(index, (byte) value)) {
                        info.setBlock(bitBlock);
                        info.setBitIndex(index);
                        if(mark) {
                            pixels[info.getY()][info.getX()] = Pixel.BLACK;
                        }
                    } else {
                        if(info.isHardZero()) {
                            throw new QArtException("Hard zero can not set");
                        }
                        if(mark) {
                            pixels[info.getY()][info.getX()] = new Pixel(0); //todo will cause error?
                        }
                    }
                }
                bitBlock.copyOut();

                boolean cheat = false;
                for (int i = 0; i < numberOfDataBytesPerBlock*8; i++) {
                    PixelInfo info = pixelByOffset[dataOffset+i];
                    Pixel pixel = pixels[info.getY()][info.getX()];
                    if ((bitBlock.getBlockBytes()[i/8]&(1<<(7-i&7))) != 0) {
                        pixel.xorPixelValue(Pixel.BLACK.getPixelValue());
                    }
                    expect[info.getY()][info.getX()] = ((pixel.getPixelValue()&Pixel.BLACK.getPixelValue()) != 0);
                    if (cheat) {
                        pixels[info.getY()][info.getX()] = new Pixel((pixel.getPixelValue()&Pixel.BLACK.getPixelValue()));
                    }
                }
                for (int i = 0; i < numberOfCheckBytesPerBlock*8; i++) {
                    PixelInfo info = pixelByOffset[plan.getNumberOfDataBytes()*8 + checkOffset + i];
                    Pixel pixel = pixels[info.getY()][info.getX()];

                    if ((bitBlock.getBlockBytes()[numberOfDataBytesPerBlock+i/8]&(1<<(7-i&7))) != 0) {
                        pixel.xorPixelValue(Pixel.BLACK.getPixelValue());
                    }
                    expect[info.getY()][info.getX()] = ((pixel.getPixelValue()&Pixel.BLACK.getPixelValue()) != 0);
                    if (cheat) {
                        pixels[info.getY()][info.getX()] = new Pixel((pixel.getPixelValue()&Pixel.BLACK.getPixelValue()));
                    }
                }

                dataOffset += numberOfDataBytesPerBlock * 8;
                checkOffset += numberOfCheckBytesPerBlock * 8;
            }

            // Pass over all pixels again, dithering.
            if (this.dither) {
                for(int i = 0;i < pixelByOffset.length;i++) {
                    PixelInfo info = pixelByOffset[i];
                    info.setDitherTarget(info.getTarget());
                }
                for(int y = 0;y < pixels.length;y++) {
                    Pixel[] row = pixels[y];
                    for(int x = 0;x < row.length;x++) {
                        Pixel pixel = row[x];
                        if (pixel.getPixelRole() != Pixel.PixelRole.DATA && pixel.getPixelRole() != Pixel.PixelRole.CHECK) {
                            continue;
                        }
                        PixelInfo info = pixelByOffset[pixel.getOffset()];
                        if (info.getBlock() == null) {
                            // did not choose this pixel
                            continue;
                        }

                        pixel = info.getPixel();

                        byte pixelValue = 1;
                        int grayValue = 0;
                        int targ = info.getDitherTarget();

                        if (targ >= 128) {
                            // want white
                            pixelValue = 0;
                            grayValue = 255;
                        }
                        byte bitValue = pixelValue;
                        if ((pixel.getPixelValue()&Pixel.INVERT.getPixelValue()) != 0) {
                            bitValue ^= 1;
                        }
                        if (info.isHardZero() && bitValue != 0) {
                            bitValue ^= 1;
                            pixelValue ^= 1;
                            grayValue ^= 0xFF;
                        }

                        // Set pixel value as we want it.
                        info.getBlock().reset(info.getBitIndex(), bitValue);

                        int error = targ - grayValue;

                        if (x+1 < row.length) {
                            addDither(pixelByOffset, row[x+1], error*7/16);
                        }
//                        if (false && y+1 < pixels.length) {
//                            if (x > 0) {
//                                addDither(pixelByOffset, pixels[y+1][x-1], error*3/16);
//                            }
//                            addDither(pixelByOffset, pixels[y+1][x], error*5/16);
//                            if (x+1 < row.length) {
//                                addDither(pixelByOffset, pixels[y+1][x+1], error*1/16);
//                            }
//                        }
                    }
                }

                for(int i = 0;i < bitBlocks.length;i++) {
                    bitBlocks[i].copyOut();
                }

            }

            errorCount = 0;
            // Copy numbers back out.
            for (int i = 0; i < dataBitsRemaining/10; i++) {
                // Pull out 10 bits.
                int v = 0;
                for (int j = 0; j < 10; j++) {
                    int index = headSize + 10*i + j;
                    v <<= 1;
                    v |= ((data[index/8] >> (7 - index&7)) & 1);
                }
                // Turn into 3 digits.
                if (v >= 1000) {
                    // Oops - too many 1 bits.
                    // We know the 512, 256, 128, 64, 32 bits are all set.
                    // Pick one at random to clear.  This will break some
                    // checksum bits, but so be it.
                    LOGGER.debug("oops, i: {}, v: {}", i, v);
                    PixelInfo info = pixelByOffset[headSize + 10*i + 3];
                    info.setContrast(Integer.MAX_VALUE >> 8);
                    info.setHardZero(true);
                    errorCount++;
                }
                numbers[i*3+0] = (byte) (v/100 + '0');
                numbers[i*3+1] = (byte) (v/10%10 + '0');
                numbers[i*3+2] = (byte) (v%10 + '0');
            }
        } while (errorCount > 0);

        Bits finalBits = new Bits();
        new Raw(url).encode(finalBits, plan.getVersion());
        new Number(new String(numbers)).encode(finalBits, plan.getVersion());
        finalBits.addCheckBytes(plan.getVersion(), plan.getLevel());

//        if(!Arrays.equals(finalBits.getBits(), bits.getBits())) {
//            LOGGER.warn("mismatch\n{} {}\n{} {}\n", bits.getBits().length, bits.getBits(), finalBits.getBits().length, finalBits.getBits());
//            throw new QArtException("byte mismatch");
//        }

        BitMatrix bitMatrix = Plan.encode(plan, scale, quietZone, new Raw(url), new Number(new String(numbers)));

//        if !m.Dither {
//            for y, row := range expect {
//                for x, pix := range row {
//                    if cc.Black(x, y) != pix {
//                        println("mismatch", x, y, p.Pixel[y][x].String())
//                    }
//                }
//            }
//        }
//
//        m.Code = &qr.Code{Bitmap: cc.Bitmap, Size: cc.Size, Stride: cc.Stride, Scale: m.Scale}
//
//        if m.SaveControl {
//            m.Control = pngEncode(makeImage(req, "", "", 0, cc.Size, 4, m.Scale, func(x, y int) (rgba uint32) {
//                pix := p.Pixel[y][x]
//                if pix.Role() == coding.Data || pix.Role() == coding.Check {
//                    pinfo := &pixByOff[pix.Offset()]
//                    if pinfo.Block != nil {
//                        if cc.Black(x, y) {
//                            return 0x000000ff
//                        }
//                        return 0xffffffff
//                    }
//                }
//                if cc.Black(x, y) {
//                    return 0x3f3f3fff
//                }
//                return 0xbfbfbfff
//            }))
//        }

        return bitMatrix;
    }

    private void addDither(PixelInfo[] pixelByOffset, Pixel pixel, int error) {
        Pixel.PixelRole role = pixel.getPixelRole();
        if (role != Pixel.PixelRole.DATA && role != Pixel.PixelRole.CHECK) {
            return;
        }

        PixelInfo info = pixelByOffset[pixel.getOffset()];

        info.setDitherTarget(info.getDitherTarget() + error);
    }

    public static final class Target {
        public byte target;
        public int contrast;

        public Target(byte target, int contrast) {
            this.target = target;
            this.contrast = contrast;
        }
    }
}
