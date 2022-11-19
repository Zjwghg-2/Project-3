import java.io.*;
import java.net.ConnectException;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.concurrent.*;

public class Node extends Thread {
    private final static long PERIOD = 2000;
    private final static int RETRY = 5;
    private final int ID, netID;
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
     * @param netID network ID
     * @param debugInfo print progress to terminal
     */
    public Node(int port, int ID, int netID, boolean debugInfo){
        this.port = port;
        this.ID = ID;
        this.netID = netID;
        this.finished = false;
        this.terminated = false;
        this.debugInfo = debugInfo;
        this.outgoing = new LinkedList<>();
        //initialize file
        File outputFile = new File("node" + this.netID + "_" + this.ID + "output.txt");
        try {
            if(outputFile.createNewFile() && debugInfo) {
                System.out.println("Node " + this.netID + ":" + this.ID + ": created output file.");
            }
        } catch (IOException e) {
            System.out.println("Node + " + this.netID + ":" + this.ID + ": could not create output file.  See stack trace.");
            e.printStackTrace();
        }
        //Initialize data from file
        try{
            //sequence number counter
            int SN = 0;
            //Set scanner to read config file
            Scanner scanner = new Scanner(new File("node" + this.netID + "_" + this.ID + ".txt"));
            //Populate data list
            while (scanner.hasNextLine()){
                //get data line
                String[] s = scanner.nextLine().split(": ");
                //pull out destination info from data line
                String[] d = s[0].split("_");
                //add frame to outgoing message queue
                outgoing.add(new Frame(netID, ID,  Integer.parseInt(d[0]), Integer.parseInt(d[1]), SN, s[1]));
                //increment SN
                SN++;
            }
            //add in completion control message
            outgoing.add(new Frame(netID, ID, netID, 0,  SN, 5));
            scanner.close();
            if(debugInfo) System.out.println("Node " + this.netID + ":" + this.ID + ": data successfully loaded: " + outgoing);
        } catch(FileNotFoundException e){
            System.out.println("Node " + this.netID + ":" + this.ID + ": An error occurred loading input file: FileNotFoundException\n");
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
                    //send acknowledgement to switch
                    out.write(new Frame(netID, ID, netID, 0, 0, 3).encode());
                    out.flush();
                    flag = true;
                    Thread.sleep(50);
                    break;
                } catch (EOFException e){
                    //end of file will send an exception, so this catches it, which then breaks out of the while loop.
                    hasContent = false;
                } catch (FrameLostException e) {
                    System.out.println("Error: Node " + netID + ":" + ID + ": could not initialize socket (init frame lost)");
                    e.printStackTrace();
                    return false;
                } catch (InterruptedException e) {
                    System.out.println("Error: Node " + netID + ":" + ID + ": unknown system interrupt encountered");
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
                if(debugInfo) System.out.println("Node " + netID + ":" + ID + ": Connection failed, retrying...");
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
                System.out.println("Node " + netID + ":" + ID + ": Couldn't find port to connect");
                return;
            }
            //connect until it works
            while(flag) {
                try {
                    init = new Socket("localhost", port);
                    flag = false;
                } catch (ConnectException e) {
                    if(debugInfo) System.out.println("Node " + netID + ":" + ID + ": Connection failed, retrying...");
                }
            }
            if(debugInfo) System.out.println("Node " + netID + ":" + ID + ": Init socket connected");
            //Initialize server socket and continue
            if(!initialize()){
                //something went wrong
                System.out.println("Error: Node " + netID + ":" + ID + " Could not initialize communication. Terminating node.");
                return;
            }
            if(debugInfo) System.out.println("Node " + netID + ":" + ID + ": Communication socket established");


            //Create streams and writers
            this.out = new BufferedOutputStream(server.getOutputStream(), 257);
            this.in = new DataInputStream(new BufferedInputStream(server.getInputStream()));
            FileWriter fileWriter = new FileWriter("node" + this.netID + "_" + this.ID + "output.txt");
            //periodic message control fields
            long start = 0;
            int repeat = 0;
            boolean waitOnAck = false;
            Frame outMsg = null;

            //Run until socket closes
            while(!server.isClosed()){
                //check for period on a message
                if(waitOnAck){
                    //check for repeat. if timeout, print and move on
                    if(repeat == RETRY){
                        System.out.println("Node " + netID + ":" + ID + ": could not send message; timeout");
                        waitOnAck = false;
                    }
                    //if time has gone beyond period threshold, add the message to the queue again
                    else if(System.currentTimeMillis() - start >= PERIOD){
                        outgoing.push(outMsg);
                        repeat++;
                    }
                }
                //send messages over socket from queue until all messages are sent
                //this block will not execute unless ack has been received; only 1 message at a time is to be sent.
                if(!outgoing.isEmpty() && !waitOnAck){
                    outMsg = outgoing.remove();
                    out.write(outMsg.encode());
                    //send message
                    out.flush();
                    //start time
                    start = System.currentTimeMillis();
                    //flag
                    waitOnAck = true;
                    if(debugInfo) System.out.println("Node " + netID + ":" + ID + ": sending message \"" + outMsg.getData() + "\"");
                    //the final message is a control message to the switch. This node can mark itself as finished though.
                    if(outgoing.isEmpty()) {
                        this.finished = true;
                    }
                }
                //listen for incoming messages
                //loop here will read whenever there is data to read
                if(in.available()>0) {
                    try{
                        //this will decode one frame's worth of data and throw exceptions where needed
                        Frame msg = Frame.decodeFromChannel(in);
                        //check for control message
                        if(msg.getSource()[2] == 0){
                            //only control message to be sent is ack 5 so no need to check what it is
                            //ack back to switch
                            out.write(new Frame(netID, ID, netID, 0, msg.getSN(), 3).encode());
                            out.flush();
                            //node can finish execution
                            break;
                        }
                        //check if message is actually for this node
                        if(msg.getDest()[0] == netID && msg.getDest()[1] == ID){
                            if(debugInfo) System.out.println("Node " + netID + ":" + ID + ": received: \"" + msg.getData() + "\"");
                            //check crc data viability
                            if(msg.getCrc() != msg.calcCrc()){
                                System.out.println("Node " + netID + ":" + ID + ": received garbage frame");
                                out.write(new Frame(netID, ID, msg.getSource()[0], msg.getSource()[1], msg.getSN(), 2).encode());
                                out.flush();
                            }
                            //data frame handle
                            else if(msg.getSize() > 0){
                                //send ack first
                                out.write(new Frame(netID, ID, msg.getSource()[0], msg.getSource()[1], msg.getSN(), 3).encode());
                                out.flush();
                                //pull data and send to file
                                fileWriter.write(msg.getSource()[0] + "_" + msg.getSource()[1] + ": " + msg.getData() + "\n");
                                fileWriter.flush();
                            }
                            //ack frame handle
                            else{
                                //message received
                                if(msg.getAck() == 3){
                                    //send message block always executes before this, and there is always at least 1 message for a node to send
                                    //thus this assert should pass, it's just important for the if statement after.
                                    assert outMsg != null;
                                    //check if the SN matches the pending ack. if it's not, ignore the message, it's probably an error.
                                    //If it is in fact an error, then we'll get another ack when the executor re-sends the message anyway.
                                    if(outMsg.getSN() != msg.getSN()) continue;
                                    //at this point: ack has been received for pending message. clear for next message.
                                    waitOnAck = false;
                                }
                                //some error happened, so resend the message
                                else outgoing.push(outMsg);
                                //response received so set repeat back to 0
                                repeat = 0;
                            }
                        }
                    } catch (FrameLostException e){
                        //Frame was lost; print this to terminal and send no ack
                        System.out.println("Frame error detected at NodeThread ID: " + this.ID);
                    }
                }
            }
            //Node has finished
            if(debugInfo) System.out.println("Node " + netID + ":" + ID + ": finished");
            this.terminated = true;
        } catch (IOException e) {
            System.out.println("IO error in Node " + netID + ":" + ID + ". Likely could not create socket, or could not" +
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
                System.out.println("Error: Node " + netID + ":" + ID + " could not properly close socket for unknown reasons.");
                e.printStackTrace();
            }
        }
    }
}