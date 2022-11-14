import java.io.*;
import java.net.ConnectException;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Scanner;

public class Node extends Thread {
    private final int ID;
    private int port;
    private boolean finished, terminated;
    private final boolean debugInfo;
    private final LinkedList<Frame> outgoing;
    private Socket server, init;
    BufferedOutputStream out;
    DataInputStream in;

    /**
     * Node constructor
     * @param port Switch server main port
     * @param ID node ID
     * @param debugInfo print progress to terminal
     */
    public Node(int port, int ID, boolean debugInfo){
        this.port = port;
        this.ID = ID;
        this.finished = false;
        this.terminated = false;
        this.debugInfo = debugInfo;
        this.outgoing = new LinkedList<>();
        //initialize file
        File outputFile = new File("node" + this.ID + "output.txt");
        try {
            if(outputFile.createNewFile() && debugInfo) {
                System.out.println("Node " + this.ID + ": created output file.");
            }
        } catch (IOException e) {
            System.out.println("Node + " + this.ID + ": could not create output file.  See stack trace.");
            e.printStackTrace();
        }
        //Initialize data from file
        try{
            //Set scanner to read config file
            Scanner scanner = new Scanner(new File("node"+this.ID+".txt"));
            //Populate data list
            while (scanner.hasNextLine()){
                String[] s = scanner.nextLine().split(": ");
                outgoing.add(new Frame(ID, Integer.parseInt(s[0]), s[1]));
            }
            scanner.close();
            if(debugInfo) System.out.println("Node " + this.ID + ": data successfully loaded: " + outgoing);
        } catch(FileNotFoundException e){
            System.out.println("Node " + this.ID + ": An error occurred loading input file: FileNotFoundException\n");
            e.printStackTrace();
        }
    }

    /**
     * Init helper function
     * <p>Establishes secondary communication socket and closes init</p>
     * @return false on failure, true on success
     */
    private boolean initialize() throws IOException{
        //streams
        this.out = new BufferedOutputStream(this.init.getOutputStream(), 257);
        this.in = new DataInputStream(new BufferedInputStream(this.init.getInputStream()));
        //get port message
        boolean flag = false;
        while(!flag){
            boolean hasContent= true;
            while(hasContent){
                try{
                    //will get new message to create a new socket on a convenient port
                    Frame f = Frame.decodeFromChannel(in);
                    this.port = Integer.parseInt(f.getData());
                    //send acknowledgement
                    out.write(new Frame(ID, 0, "").encode());
                    out.flush();
                    flag = true;
                    Thread.sleep(50);
                    break;
                } catch (EOFException e){
                    //end of file will send an exception, so this catches it, which then breaks out of the while loop.
                    hasContent = false;
                } catch (FrameLostException e) {
                    System.out.println("Error: Node " + ID + ": could not initialize socket (init frame lost)");
                    e.printStackTrace();
                    return false;
                } catch (InterruptedException e) {
                    System.out.println("Error: Node " + ID + ": unknown system interrupt encountered");
                    e.printStackTrace();
                    return false;
                }
            }
        }
        //init can be closed
        this.out.close();
        this.in.close();
        this.init.close();
        //open server socket; connect until it works
        while(flag) {
            try {
                server = new Socket("localhost", port);
                flag = false;
            } catch (ConnectException e) {
                if(debugInfo) System.out.println("Node " + ID + ": Connection failed, retrying...");
            }
        }
        return true;
    }

    /**
     * Executable code
     */
    public void run(){
        server = null;
        init = null;
        try {
            //Server connection begin
            boolean flag = true;
            //check if the port exists
            if(port == -1) {
                System.out.println("Node " + ID + ": Couldn't find port to connect");
                return;
            }
            //connect until it works
            while(flag) {
                try {
                    init = new Socket("localhost", port);
                    flag = false;
                } catch (ConnectException e) {
                    if(debugInfo) System.out.println("Node " + ID + ": Connection failed, retrying...");
                }
            }
            if(debugInfo) System.out.println("Node " + ID + ": Init socket connected");
            //Initialize server socket and continue
            if(!initialize()){
                //something went wrong
                System.out.println("Error: Node " + ID + " Could not initialize communication. Terminating node.");
                return;
            }
            if(debugInfo) System.out.println("Node " + ID + ": Communication socket established");
            //Create streams and writers
            this.out = new BufferedOutputStream(server.getOutputStream(), 257);
            this.in = new DataInputStream(new BufferedInputStream(server.getInputStream()));
            FileWriter fileWriter = new FileWriter("node"+this.ID+"output.txt");
            //Run until socket closes
            while(!server.isClosed()){
                //send messages over socket from queue until all messages are sent
                if(!outgoing.isEmpty()){
                    Frame msg = outgoing.remove();
                    out.write(msg.encode());
                    if(debugInfo) System.out.println("Node " + ID + ": sending message \"" + msg.getData() + "\"");
                    out.flush();
                    //the final message will be "stop", so if that's the case, break out of the loop.
                    //if(S.equalsIgnoreCase("stop")) break;
                    //now that this has sent, check if we're finished
                    if(outgoing.isEmpty()) {
                        //we are finished, so set flag and inform server with a control message
                        this.finished = true;
                        out.write(new Frame(ID, 0, "fin").encode());
                        if(debugInfo) System.out.println("Node " + ID + ": sending control message \"fin\"");
                        out.flush();
                    }
                }
                //listen for incoming messages
                //loop here will read whenever there is data to read
                if(in.available()>0){
                    try{
                        //this will decode one frame's worth of data and throw exceptions where needed
                        Frame msg = Frame.decodeFromChannel(in);
                        //check for control message
                        if(msg.getSource() == 0){
                            //only control message to be sent is "fin" so no need to check what it is
                            //ack to server
                            out.write(new Frame(ID, 0, "").encode());
                            out.flush();
                            //node can finish execution
                            break;
                        }
                        //check if message is actually for this node
                        if(msg.getDest() == ID){
                            if(debugInfo) System.out.println("Node " + ID + ": received: \"" + msg.getData() + "\"");
                            //pull data and send to file
                            fileWriter.write(msg.getSource() + ": " + msg.getData() + "\n");
                            fileWriter.flush();
                        }
                    } catch (FrameLostException e){
                        //Frame was lost; print this to terminal and send no ack
                        System.out.println("Frame error detected at NodeThread ID: " + this.ID);
                    }
                }
            }
            //Node has finished
            if(debugInfo) System.out.println("Node " + ID + ": finished");
            this.terminated = true;
        } catch (IOException e) {
            System.out.println("IO error in node " + ID + ". Likely could not create socket, or could not" +
                    "write to output file. See stack trace for details.");
            e.printStackTrace();
        } catch (Exception e){
            System.out.println("Unknown error encountered in node" + ID + ". See stack trace for details.");
            e.printStackTrace();
        }
        finally {
            //if this happens from an error break, the node is in fact finished, albeit forcibly.
            finished = true;
            //close sockets
            try {
                if(server != null){
                    server.close();
                }
                if(init != null){
                    init.close();
                }
            } catch (IOException e){
                System.out.println("Error: Node " + ID + " could not properly close socket for unknown reasons.");
                e.printStackTrace();
            }
        }
    }
}