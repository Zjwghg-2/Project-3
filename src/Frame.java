import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

/**
 * Frame object class
 * Format: [SourceNetwork][SourceNode][DestNetwork][DestNode][SequenceNumber][CRC][Size][Ack/Data]
 */
public class Frame {
    private final int sourceNode, sourceNet, destNode, destNet, ack, size, sequence;
    byte crc;
    private final String data;

    /**
     * Frame class constructor (data)
     * @param sourceNet source network (-1 if unknown, 0 if control)
     * @param sourceNode source node ID
     * @param destNet destination network (0 if control)
     * @param destNode destination node ID
     * @param sequence frame sequence number
     * @param data Data value (send "" for ack)
     */
    public Frame(int sourceNet, int sourceNode, int destNet, int destNode, int sequence, String data){
        this.sourceNode = sourceNode;
        this.sourceNet = sourceNet;
        this.destNode = destNode;
        this.destNet = destNet;
        this.sequence = sequence;
        this.size = data.length();
        this.data = data;
        this.crc = this.calcCrc();
        //ack is not sent, so this is just a placeholder value since ack is defined as an object field
        this.ack = -1;
    }
    /**
     * Frame class constructor (ack)
     * @param sourceNet source network (-1 if unknown, 0 if control)
     * @param sourceNode source node ID
     * @param destNet destination network (0 if control)
     * @param destNode destination node ID
     * @param sequence sequence number
     * @param ack Ack value (unique implementation)
     */
    public Frame(int sourceNet, int sourceNode, int destNet, int destNode, int sequence, int ack){
        this.sourceNode = sourceNode;
        this.sourceNet = sourceNet;
        this.destNode = destNode;
        this.destNet = destNet;
        this.sequence = sequence;
        this.size = 0;
        this.ack = ack;
        this.crc = this.calcCrc();
        //data is not sent, so this is just a placeholder value since ack is defined as an object field
        this.data = "";
    }


    /*-------------Methods--------------*/


    /**
     * Get source from frame
     * @return size 2 integer array; 0 is network ID, 1 is node ID
     */
    public int[] getSource(){
        return new int[]{this.sourceNet, this.sourceNode};
    }
    /**
     * Get dest from frame
     * @return size 2 integer array; 0 is network ID, 1 is node ID
     */
    public int[] getDest(){
        return new int[]{this.destNet, this.destNode};
    }

    //other getters
    public int getSize(){return this.size;}
    public int getAck(){return this.ack;}
    public String getData(){return this.data;}
    public byte getCrc(){return this.crc;}
    public int setSN(){return this.sequence;}

    //calculate expected crc byte value for a given frame
    public byte calcCrc(){
        byte a = 0;
        a+= this.sourceNet + this.sourceNode + this.destNet + this.destNode + this.sequence;
        if(this.size > 0){
            //if data frame use data
            a+=this.size + data.getBytes()[0];
        } else {
            //if ack frame use ack
            a+=this.ack;
        }
        return a;
    }

    //private helper function
    private void setCrc(byte crc){this.crc = crc;}

    /**
     * Encode frame into byte array message
     * <p>Uses default system string encoding</p>
     * @return frame message
     */
    public byte[] encode(){
        byte[] out;
        if(this.size > 0){
            //data frame
            out = new byte[7+this.size];
        } else {
            //ack frame
            out = new byte[8];
        }
        //set standard values
        out[0] = (byte) this.sourceNet;
        out[1] = (byte) this.sourceNode;
        out[2] = (byte) this.destNet;
        out[3] = (byte) this.destNode;
        out[4] = (byte) this.sequence;
        out[5] = this.crc;
        out[6] = (byte) this.size;
        if (this.size > 0){
            //set data if data frame
            System.arraycopy(data.getBytes(), 0, out, 7, this.size);
        } else {
            //set ack if ack frame
            out[7] = (byte) ack;
        }
        return out;
    }

    /**
     * Parse byte message as frame message
     * <p>Uses default system string encoding</p>
     * @return new frame object
     */
    public static Frame decode(byte[] msg) throws FrameLostException{
        Frame f;
        //read in standard 5 values
        int sourceNet = Byte.toUnsignedInt(msg[0]), sourceNode = Byte.toUnsignedInt(msg[1]),
                destNet = Byte.toUnsignedInt(msg[2]), destNode = Byte.toUnsignedInt(msg[3]),
                sequence = Byte.toUnsignedInt(msg[4]), size = Byte.toUnsignedInt(msg[6]);
        byte crc = msg[5];
        //data frame
        if(size > 0){
            //check for loss
            if(msg.length != 7 + size) throw new FrameLostException("Frame decoding detected data loss");
            //parse data and frame it
            String data = new String(Arrays.copyOfRange(msg, 6, 6+size));
            f = new Frame(sourceNet, sourceNode, destNet, destNode, sequence, data);
        }
        //ack frame
        else{
            //check for loss
            if(msg.length != 8) throw new FrameLostException("Frame decoding detected data loss");
            //parse data and frame it
            int ack = Byte.toUnsignedInt(msg[7]);
            f = new Frame(sourceNet, sourceNode, destNet, destNode, sequence, ack);
        }
        //set crc (ensure original crc tag is used in case of data failure)
        f.setCrc(crc);
        return f;
    }

    /**
     * Parse byte message as frame message
     * <p>Uses default system string encoding</p>
     * @param in input stream (thread)
     * @return new frame object
     */
    public static Frame decodeFromChannel(DataInputStream in) throws FrameLostException, IOException {
        int sourceNet, sourceNode, destNet, destNode, size, sequence;
        byte crc;
        Frame f;
        //read standard parameters
        try {
            //read header
            sourceNet = in.read();
            sourceNode = in.read();
            destNet = in.read();
            destNode = in.read();
            sequence = in.read();
            crc = in.readByte();
            size = in.read();
        } catch (EOFException e){
            //EOF was encountered unexpectedly here, so there has been data loss.
            throw new FrameLostException("Frame decoding detected data loss");
        }
        //ack frame
        if (size == 0){
            int ack;
            try{
                ack = in.read();
            } catch (EOFException e){
                //EOF was encountered unexpectedly here, so there has been data loss.
                throw new FrameLostException("Frame decoding detected data loss");
            }
            f = new Frame(sourceNet, sourceNode, destNet, destNode, sequence, ack);
        }
        //data frame
        else {
            byte[] data = in.readNBytes(size);
            //Verify data size and return
            if(data.length != size) throw new FrameLostException("Frame decoding detected data loss");
            f = new Frame(sourceNet, sourceNode, destNet, destNode, sequence, new String(data));
        }
        //set cyc (ensure original crc is used in case of error)
        f.setCrc(crc);
        return f;
    }

    @Override
    public String toString(){
        if(this.size >= 0){
            return "[" + sourceNet + ":" + sourceNode + "][" + destNet + ":" + destNode  + "][" + sequence + "][" +
                    "][" + crc + "][" + size + "][" + data + "]";
        }else{
            return "[" + sourceNet + ":" + sourceNode + "][" + destNet + ":" + destNode  + "][" + sequence + "][" +
                    "][" + crc + "][" + size + "][" + ack + "]";
        }
    }


    //A test example to show how to utilize this class, and to show that it works.
    public static void main(String[] args) throws Exception{
        Frame f1 = new Frame(0, 0, 0, 0, 0, "woah there its a message lol lmao");
        byte[] e1 = f1.encode();

        Frame f2 = Frame.decode(e1);
        System.out.println("Source: " + f2.getSource());
        System.out.println("Dest: " + f2.getDest());
        System.out.println("Message: " + f2.getData());
    }

}
