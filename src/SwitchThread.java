import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class SwitchThread extends Thread{
    private static final AtomicInteger counter;
    private final CentralSwitch server;
    private final Socket client;
    private final int ID;
    private final BufferedOutputStream out;
    private final DataInputStream in;
    private volatile boolean identified, finished, terminated, initialized;
    private final boolean debugInfo;
    //static initializer block for atomicInt counter. This variable gives unique IDs to each SwitchThread that is created.
    static {counter = new AtomicInteger();}

    /**
     * Client thread constructor
     * @param server Switch server
     */
    public SwitchThread(CentralSwitch server, Socket client, boolean debugInfo) {
        this.debugInfo = debugInfo;
        this.identified = false;
        this.finished = false;
        this.terminated = false;
        this.initialized = false;
        this.server = server;
        this.ID = counter.incrementAndGet();
        this.client = client;
        try {
            out = new BufferedOutputStream(client.getOutputStream(), 257);
            in = new DataInputStream(new BufferedInputStream(client.getInputStream()));
        } catch (IOException e) {
            System.out.println("Fatal error in SwitchThread " + ID + ": could not set up streams");
            throw new RuntimeException("Couldn't get stream");
        }
    }

    public int getID() {
        return this.ID;
    }

    public boolean finished() {
        return finished;
    }

    /**
     * Helper function: navigates closing connection with client
     */
    private void exit() throws IOException{
        //assumes streams are not closed
        //send control
        synchronized (out){
            out.write(new Frame(0, 0, 0, 0, 0, 6).encode());
            out.flush();
        }
        //get ack
        boolean acknowledged = false;
        while(!acknowledged){
            boolean hasContent= true;
            while(hasContent){
                try{
                    //ack frame. what it is this doesn't really matter, we just need any message
                    byte[] raw = in.readAllBytes();
                    //that said, I still check that *some* data was received.
                    //even if the data is corrupt or something, that doesn't matter, literally just a response is enough
                    if(raw.length > 0){
                        acknowledged = true;
                        break;
                    }
                } catch (EOFException e){
                    //end of file will send an exception, so this catches it, which then breaks out of the while loop.
                    hasContent = false;
                }
            }
        }
    }

    /**
     * Send a new message to the communication thread
     * @param message message
     */
    public void newMessage(Frame message){
        if(!this.initialized) Thread.onSpinWait();
        try{
            if(debugInfo) System.out.println("SwitchThread " + ID + ": sending: " + message);
            synchronized (out){
                out.write(message.encode());
                out.flush();
            }
        } catch (SocketException e) {
            System.out.println("Error: SwitchThread " + ID + ": could not send message to client: likely socket closed.");
            e.printStackTrace();
        } catch (IOException e){
            System.out.println("Error: SwitchThread " + ID + ": unknown IO Exception encountered. See stack trace.");
            e.printStackTrace();
        }
    }

    /**
     * Allow server to terminate connections
     */
    @Override
    public void interrupt(){
        this.terminated = true;
    }

    /**
     * Execution code
     */
    @Override
    public void run() {
        try {
            if(debugInfo) System.out.println("SwitchThread " + ID + ": Connection thread successfully established");
            this.initialized = true;
            //read until the connection closes or until instructed to terminate
            while(!terminated && !client.isClosed()){
                //loop here will read whenever there is data to read
                while(in.available()>0){
                    try{
                        //this will decode one frame's worth of data and throw exceptions where needed
                        Frame msg = Frame.decodeFromChannel(in);
                        //This basically does the job of """"learning"""" from incoming messages
                        if(!identified){
                            //add table entry (pass network ID; the central switch's table works on the network level)
                            server.addEntry(ID, msg.getSource()[0]);
                            identified = true;
                            if(debugInfo) System.out.println("SwitchThread " + ID + ": connected client identified, " + msg.getSource()[0]);
                        }
                        //check for control message
                        if(msg.getDest()[1] == 0){
                            //the only implemented control message is "fin" so no need to check for others
                            //node is done sending data, so we no longer need to do this loop
                            this.finished = true;
                            //inform switch
                            if(debugInfo) System.out.println("SwitchThread " + ID + ": control message identified " + msg);
                            server.checkFinished();
                        }
                        //not control, so it's an actual data message
                        else {
                            if(debugInfo) System.out.println("SwitchThread " + ID + ": outgoing message sent to switch " + msg);
                            //add it to the server's buffer to be switched as appropriate
                            this.server.enqueueMessage(msg);
                        }
                    } catch (FrameLostException e){
                        //Frame was lost; print this to terminal and send no ack
                        System.out.println("Frame error detected at SwitchThread ID: " + this.ID);
                    }
                }
            }
            if(debugInfo) System.out.println("SwitchThread " + ID + ": work complete");
        } catch (IOException e) {
            //something went wrong with I/O
            System.out.println("An IO error occurred in SwitchThread with ID " + ID + ". Likely,  could not create socket."
                    + "  Stack trace is shown below.");
            e.printStackTrace();
        } finally {
            //close socket if not already closed
            try {
                if(client != null){
                    //write closing message to socket
                    this.exit();
                    client.close();
                }
            } catch (IOException e){
                System.out.println("SwitchThread " + ID + ": I/O Exception occurred. Likely, could not facilitate exit.");
                e.printStackTrace();
            }
            //in case there was some error, mark as finished and inform server
            if(!this.finished){
                this.finished = true;
                server.checkFinished();
            }
        }
    }
}
