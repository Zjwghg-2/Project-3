import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

public class Frame {
    private final int source, dest, size;
    private final String data;

    /**
     * Frame class constructor
     * @param source source port
     * @param dest destination port
     * @param data Data value (send "" for ack)
     */
    public Frame(int source, int dest, String data){
        this.source = source;
        this.dest = dest;
        this.size = data.length();
        this.data = data;
    }

    public int getSource(){
        return this.source;
    }
    public int getDest(){
        return this.dest;
    }
    public int getSize(){
        return this.size;
    }
    public String getData(){
        return this.data;
    }

    /**
     * Encode frame into byte array message
     * <p>Uses default system string encoding</p>
     * @return frame message
     */
    public byte[] encode(){
        byte[] out = new byte[3+this.size];
        out[0] = (byte) this.source;
        out[1] = (byte) this.dest;
        out[2] = (byte) this.size;
        if (this.size > 0) System.arraycopy(data.getBytes(), 0, out, 3, this.size);
        return out;
    }

    /**
     * Parse byte message as frame message
     * <p>Uses default system string encoding</p>
     * @return new frame object
     */
    public static Frame decode(byte[] msg) throws FrameLostException{
        //read in standard 3 values
        int source = Byte.toUnsignedInt(msg[0]), dest = Byte.toUnsignedInt(msg[1]), size = Byte.toUnsignedInt(msg[2]);
        //check for data loss
        if(msg.length != 3 + size) throw new FrameLostException("Frame decoding detected data loss");
        //parse data and return
        String data = new String(Arrays.copyOfRange(msg, 3, 3+size));
        return new Frame(source, dest, data);
    }

    /**
     * Parse byte message as frame message
     * <p>Uses default system string encoding</p>
     * @param in input stream (thread)
     * @return new frame object
     */
    public static Frame decodeFromChannel(DataInputStream in) throws FrameLostException, IOException {
        int source = 0, dest = 0, size = 0;
        //read standard parameters
        try {
            //read 1 frame of data
            source = in.read();
            dest = in.read();
            size = in.read();
        } catch (EOFException e){
            //EOF was encountered unexpectedly here, so there has been data loss.
            throw new FrameLostException("Frame decoding detected data loss");
        }
        //Check for ack frame
        if (size == 0) return new Frame(source, dest, "");
        //Not ack frame, get data
        byte[] data = in.readNBytes(size);
        //Verify data size and return
        if(data.length != size) throw new FrameLostException("Frame decoding detected data loss");
        return new Frame(source, dest, new String(data));
    }

    public String toString(){
        return "[" + source + "][" + dest + "][" + size + "][" + data + "]";
    }

    //A test example to show how to utilize this class, and to show that it works.
    public static void main(String[] args) throws Exception{
        Frame f1 = new Frame(0, 1, "woah there its a message lol lmao");
        byte[] e1 = f1.encode();

        Frame f2 = Frame.decode(e1);
        System.out.println("Source: " + f2.getSource());
        System.out.println("Dest: " + f2.getDest());
        System.out.println("Message: " + f2.getData());
    }

}
