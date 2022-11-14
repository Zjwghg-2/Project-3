import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;

public class NodeThread extends Thread{
    private static final AtomicInteger counter;
    private final Switch server;
    private final Socket init;
    private int port;
    private final int ID;
    private BufferedOutputStream out;
    private DataInputStream in;
    private volatile boolean identified, finished, terminated, initialized;
    private final boolean debugInfo;
    //static initializer block for atomicInt counter. This variable gives unique IDs to each NodeThread that is created.
    static {counter = new AtomicInteger();}

    /**
     * Client thread constructor
     * @param server Switch server
     */
    public NodeThread(Switch server, Socket client, boolean debugInfo) {
        this.debugInfo = debugInfo;
        this.identified = false;
        this.finished = false;
        this.terminated = false;
        this.initialized = false;
        this.server = server;
        this.ID = counter.incrementAndGet();
        this.init = client;
    }

    public int getID() {
        return this.ID;
    }

    public boolean finished() {
        return finished;
    }

    /**
     * Init helper function
     * <p>Establishes secondary communication socket and closes init</p>
     */
    private void initialize() throws IOException{
        //streams
        out = new BufferedOutputStream(this.init.getOutputStream(), 257);
        in = new DataInputStream(new BufferedInputStream(this.init.getInputStream()));
        //send port
        out.write(new Frame(0, 0, String.valueOf(port)).encode());
        out.flush();
        //get ack
        boolean acknowledged = false;
        while(!acknowledged){
            boolean hasContent= true;
            while(hasContent){
                try{
                    //ack frame with dummy address 0, really this doesn't matter, we just need any message
                    byte[] raw = in.readAllBytes();
                    //that said, I still check that it has a size, because why not
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
        //ack received, so it is safe to close init
        this.init.close();
    }

    /**
     * Helper function: navigates closing connection with client
     */
    private void exit() throws IOException{
        //assumes streams are not closed
        //send port
        out.write(new Frame(0, 0, "fin").encode());
        out.flush();
        //get ack
        boolean acknowledged = false;
        while(!acknowledged){
            boolean hasContent= true;
            while(hasContent){
                try{
                    //ack frame with dummy address 0, really this doesn't matter, we just need any message
                    byte[] raw = in.readAllBytes();
                    //that said, I still check that it has a size, because why not
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
        //rarely, this method well be called before initialization is finished. so, ensure this is accounted for
        //this will, unfortunately, block the switch until the node finishes init, but it prevents data loss.
        while(!initialized) Thread.onSpinWait();
        try{
            if(debugInfo) System.out.println("NodeThread " + ID + ": incoming message identified: " + message);
            out.write(message.encode());
            out.flush();
        } catch (SocketException e) {
            System.out.println("Error: NodeThread " + ID + ": could not send message to client; socket closed.");
            e.printStackTrace();
        } catch (IOException e){
            System.out.println("Error: NodeThread " + ID + ": unknown IO Exception encountered. See stack trace.");
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
        Socket client = null;
        ServerSocket serverSocket = null;
        try {
            //start new connection on any port
            serverSocket = new ServerSocket(0);
            this.port = serverSocket.getLocalPort();
            this.initialize();
            //listen for incoming connection on new socket
            client = serverSocket.accept();
            if(debugInfo) System.out.println("NodeThread " + ID + ": Connection thread successfully established");
            //set up i/o  - now featuring bytes because we're using bytes for some reason
            //why are you obsessed with obfuscating every single task in these assignments for no reason
            this.out = new BufferedOutputStream(client.getOutputStream(), 257);
            this.in = new DataInputStream(new BufferedInputStream(client.getInputStream()));
            //NOW, mark self as initialized -- the communication socket is open and the streams are created.
            this.initialized = true;
            //read until the connection closes or until instructed to terminate
            while(!terminated && !client.isClosed()){
                //loop here will read whenever there is data to read
                while(in.available()>0){
                    try{
                        //this will decode one frame's worth of data and throw exceptions where needed
                        Frame msg = Frame.decodeFromChannel(in);
                        //This basically does the job of """"learning"""" from incoming messages
                        /*Yes it has to be done here and not in Switch, because of limitations of the language and
                          because I again have to actively fight against the language to implement this requirement*/
                        if(!identified){
                            server.addEntry(ID, msg.getSource());
                            identified = true;
                            if(debugInfo) System.out.println("NodeThread " + ID + ": connected client identified");
                        }
                        //check for control message
                        if(msg.getDest() == 0){
                            //the only implemented control message is "fin" so no need to check for others
                            //node is done sending data, so we no longer need to do this loop
                            this.finished = true;
                            //inform switch
                            if(debugInfo) System.out.println("NodeThread " + ID + ": control message identified");
                            server.checkFinished();
                        }
                        //not control, so it's an actual data message
                        else {
                            if(debugInfo) System.out.println("NodeThread " + ID + ": outgoing message sent to switch");
                            //add it to the server's buffer to be switched as appropriate
                            this.server.enqueueMessage(msg);
                        }
                    } catch (FrameLostException e){
                        //Frame was lost; print this to terminal and send no ack
                        System.out.println("Frame error detected at NodeThread ID: " + this.ID);
                    }
                }
            }
            if(debugInfo) System.out.println("NodeThread " + ID + ": work complete");
        } catch (IOException e) {
            //something went wrong with I/O
            System.out.println("An IO error occurred in NodeThread with ID " + ID + ". Likely,  could not create socket."
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
                if(serverSocket != null){
                    serverSocket.close();
                }
            } catch (IOException e){
                System.out.println("NodeThread " + ID + ": I/O Exception occurred. Likely, could not facilitate exit.");
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
