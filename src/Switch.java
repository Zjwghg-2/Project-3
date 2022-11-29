import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * First-level switch object class
 */
public class Switch extends Thread{
    private final int port, netID, masterPort;
    private Socket master;
    private final ArrayList<Integer> firewall;
    private final ArrayList<NodeThread> clients;
    //Format: {node ID, index for clients arraylist}.
    //In my case, "ports" are logical (arraylist index), not physical, due to Java's native socket implementation.
    private final ArrayList<Integer[]> switchTable;
    //a buffer message queue, because I have no reason to make this a static size data field.  If I am required to
    //do that, I will dispute the point, since nowhere is it implied or explicitly stated that the frame cannot be
    //a dynamic object. This is just the cleanest and easiest way to implement such a thing, despite it being extremely
    //unnecessary with java's implementation of sockets.
    private final LinkedList<Frame> buffer;
    private volatile boolean finished, completed;
    private final boolean debugInfo;
    //communicate to master
    private BufferedOutputStream out;
    private DataInputStream in;

    /**
     * Switch constructor
     * @param port Local listen port (communication port is dynamic per connection)
     * @param netID Network ID corresponding to this switch
     * @param masterPort central switch port number
     * @param debugInfo enable debug information
     */
    public Switch(int port, int netID, int masterPort, boolean debugInfo){
        this.debugInfo = debugInfo;
        this.netID = netID;
        this.port = port;
        this.master = null;
        this.completed = false;
        this.firewall = new ArrayList<>();
        this.masterPort = masterPort;
        this.clients = new ArrayList<>();
        this.buffer = new LinkedList<>();
        this.switchTable = new ArrayList<>();
    }

    public boolean isFinished(){return this.finished;}

    /**
     * Thread-safe helper function.
     * <p>Synchronized message queue access means frames are sent out in order they arrive here.
     * @param message message to queue
     */
    public void enqueueMessage(Frame message){
        synchronized (buffer){
            this.buffer.add(message);
        }
    }

    /**
     * Thread-safe helper function.
     * <p>Synchronized message queue access means frames are sent out in order they arrive here.
     */
    public Frame dequeueMessage(){
        synchronized (buffer){
            return this.buffer.remove();
        }
    }

    /**
     * Thread-safe helper function; Adds an entry into the switching table
     * <p>Called in NodeThread when an unidentified client first communicates.
     * @param ID communication thread identifier
     * @param key client identifier
     */
    public void addEntry(int ID, int key){
        int j = -1;
        synchronized (clients){
            //find client's """port""" (logical port in this case, since java's implementation doesn't work that way)
            for(int i = 0; i < clients.size(); i++){
                if(clients.get(i).getID() == ID){
                    j = i;
                    break;
                }
            }
        }
        //if for some reason the client isn't connected after sending the message, just don't do anything?
        if(j==-1) return;
        //add entry to switch table
        synchronized (switchTable){
            switchTable.add(new Integer[]{key, j});
        }
    }

    /**
     * helper function
     * <p>checks whether all clients are done sending messages
     */
    public void checkFinished() {
        try {
            //check 1
            for(NodeThread thread: this.clients){
                if(!thread.finished()) return;
            }
            //sleep for 1 second
            Thread.sleep(1000);
            //check again
            for(NodeThread thread: this.clients){
                if(!thread.finished()) return;
            }
            //reasonably certain that all nodes that will connect have connected and have finished.
            this.finished = true;
            //inform master we are finished
            synchronized (out){
                out.write(new Frame(netID, 0, 0, 0, 0, 5).encode());
                out.flush();
            }
        } catch (InterruptedException e){
            System.out.println("Server " + netID + ":Unknown system interrupt encountered... retrying...");
            checkFinished();
        } catch (IOException e){
            System.out.println("Server " + netID + ": Unknown IO exception encountered");
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        //connect to master and retrieve firewall rules
        try {
            //check if the port exists
            if(masterPort == -1) {
                System.out.println("Server " + netID + ": Couldn't find port to connect");
                return;
            }
            //connect until it works
            boolean flag = true;
            do{
                try {
                    master = new Socket("localhost", masterPort);
                    flag = false;
                } catch (ConnectException e) {
                    //if(debugInfo) System.out.println("Server " + netID + ": Connection failed, retrying...");
                }
            } while(flag);
            if(debugInfo) System.out.println("Server " + netID + ": master socket connected");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        //set up streams and read messages
        try {
            in = new DataInputStream(new BufferedInputStream(master.getInputStream()));
            out = new BufferedOutputStream(master.getOutputStream());
            boolean flag = true;
            //read
            while(flag){
                while(in.available()>0){
                    try{
                        //this will decode one frame's worth of data and throw exceptions where needed
                        Frame msg = Frame.decodeFromChannel(in);
                        //check for completion ack
                        if(msg.getDest()[0] == 0){
                            //this runs for ack 1 control message, flooded to all nodes using destination network = 0
                            flag = false;
                            if(debugInfo) System.out.println("Server " + netID + ": Ack 1 received. Setting up node connections." +
                                    " Firewall: " + firewall);
                        }
                        //not control, so it's firewall info
                        else {
                            //if not intended destination (flood), ignores the message
                            if(msg.getDest()[0] == netID){
                                if(debugInfo) System.out.println("Server " + netID + ": local firewall information received");
                                firewall.add(Integer.parseInt(msg.getData()));
                            }
                        }
                    } catch (FrameLostException e){
                        //Frame was lost; print this to terminal and send no ack
                        System.out.println("Server " + netID + ": frame error detected at firewall setup");
                    }
                }
            }
        } catch (IOException e){
            System.out.println("Server " + netID + ": Unknown IO exception occurred in firewall setup");
            e.printStackTrace();
        }

        //switch performance
        //this contains 3 threads. the new one talks to master switch

        //This is necessary for thread 1, as it is an abstract thread object, but still needs switch reference
        Switch self = this;
        //Thread 1: accepts incoming connections, creates NodeThreads to handle communication.
        //this is an abstract class, as I have to override interrupt as well as run to terminate it on cleanup.
        Thread acceptor = new Thread(){
            private ServerSocket serverSocket;
            @Override
            public void run() {
                //set up server socket
                try {
                    serverSocket = new ServerSocket(port);
                    //deadloop -- listen for client connection and handle it
                    while (!completed) {
                        //wait for client connection
                        Socket client = serverSocket.accept();
                        if(debugInfo) System.out.println("Server " + netID + ": New client connected");
                        NodeThread nodeThread = new NodeThread(self, client, debugInfo);
                        nodeThread.start();
                        //add to instance field list
                        synchronized (clients){
                            clients.add(nodeThread);
                        }
                    }
                } catch (SocketException e){
                    if(debugInfo) System.out.println("Server " + netID + ": acceptor thread forced close");
                } catch (IOException e) {
                    System.out.println("Server " + netID + ": unknown IOException encountered");
                    e.printStackTrace();
                }
            }
            @Override
            public void interrupt(){
                //I intentionally make the interrupt procedure throw an exception, since socket.accept is blocking.
                //If I don't do this, there's no way to cleanly close this thread.
                try{
                    serverSocket.close();
                } catch (IOException e){
                    if(debugInfo) System.out.println("Server " + netID + ": acceptor thread forced close");
                }
            }
        };
        //Thread 2: Manages buffer and switching messages to correct client
        //this one is a lambda, because it's cleaner, and I only need to implement run() here
        Thread manager = new Thread(() -> {
            while(!completed){
                //skip over until there is data to send
                if(buffer.isEmpty()) {
                    Thread.yield();
                    continue;
                }
                //if(debugInfo) System.out.println("Server " + netID + ": message found in buffer");
                Frame message = dequeueMessage();
                if(debugInfo) System.out.println("Server " + netID + ": found message (may be flooded)" + message);
                //Note that the NodeThread automatically informs Switch of unidentified clients (see addEntry)
                //The switch object therefore adds entries to the switching table in that method automatically
                //That is why adding entries to the switch table is not handled in this block

                //this handles outgoing traffic
                if(message.getDest()[0] != netID) {
                    //frame is local traffic going out of this network; send the message to global switch
                    try{
                        synchronized (out){
                            out.write(message.encode());
                            out.flush();
                        }
                    } catch (IOException e){
                        System.out.println("Server " + netID + ": Could not send global traffic " + message);
                        e.printStackTrace();
                    }
                    //non-local traffic has been handled, move on
                    continue;
                }

                //this handles local or incoming traffic.
                //check for firewall; if local node is firewalled, nack
                boolean flag = false;
                for(int i: firewall){
                    if(message.getDest()[1] == i){
                        //firewall found
                        //acks need to pass through the firewall
                        if(message.getSize() == 0) break;
                        //local messages need to pass through the firewall
                        if(message.getSource()[0] == netID) break;
                        //send a nack message
                        if(debugInfo) System.out.println("Server " + netID + ": message firewalled, sending nack " + message);
                        try{
                            flag = true;
                            synchronized (out){
                                out.write(new Frame(netID, message.getDest()[1], message.getSource()[0], message.getSource()[1],
                                        message.getSN(), 4).encode());
                                out.flush();
                            }
                            break;
                        } catch (IOException e){
                            System.out.println("Server " + netID + ": Unknown IO error encountered");
                            e.printStackTrace();
                        }
                    }
                }
                //check if nack was sent. if so, do not pass along message. otherwise, move on
                //this line here is what actually firewalls things
                if(flag) continue;

                //Message is both for this network and is not firewalled.
                //check switch table for sending area
                boolean found = false;
                int key = -1;
                synchronized (switchTable){
                    for(Integer[] entry : switchTable){
                        //look for destination in table
                        if(message.getDest()[1] == entry[0]){
                            //pass along the message
                            if(debugInfo) System.out.println("Server " + netID + ": message switched " + message);
                            clients.get(entry[1]).newMessage(message);
                            found = true;
                            break;
                        }
                        //this is for next block for flooding purposes; it isn't used if dest is present in the table
                        if(message.getSource()[1] == entry[0]) key = entry[1];
                    }
                }
                if(found) continue;
                //this block will only be reached if the target not found in switch table, so here we flood
                if(debugInfo) System.out.println("Server " + netID + ": message will be flooded " + message);
                synchronized (clients){
                    for(int i = 0; i < clients.size(); i++){
                        if(i == key) continue;
                        clients.get(i).newMessage(message);
                    }
                }
            }
            //manager thread is done, which means all data is finished sending.  start cleanup.
            if(debugInfo) System.out.println("Server " + netID + ": communication threads completed, starting cleanup");
            //cleanup: tell all communication threads to finish and close.
            for(NodeThread t: clients){
                t.interrupt();
            }
            //cleanup: force acceptor to close
            if(acceptor.isAlive()) acceptor.interrupt();
        });

        //master communicator
        Thread central = new Thread(() -> {
            try{
                while(!completed){
                    //read in data whenever there is data to read
                    while(in.available() > 0){
                        try{
                            Frame msg = Frame.decodeFromChannel(in);
                            if(debugInfo) System.out.println("Switch " + netID + ": received global " + msg);
                            //check for control message
                            if(msg.getAck() == 6){
                                if(debugInfo) System.out.println("Switch " + netID + ": fin received");
                                this.completed = true;
                                out.write(new Frame(0, 0, 0, 0, 0, 3).encode());
                                out.flush();
                            }
                            //check for flooded message; if so, ignore it and move on
                            else if(msg.getDest()[0] != netID) continue;
                            //must be some message to the network, then
                            else {
                               //if(debugInfo) System.out.println("Switch " + netID + ": received global " + msg);
                               enqueueMessage(msg);
                            }
                        } catch (FrameLostException e){
                            System.out.println("Switch " + netID + ": data loss detected when talking to master");
                        }
                    }
                }
                if(debugInfo) System.out.println("Switch" + netID + ": work complete");
            } catch (IOException e){
                //something went wrong with I/O
                System.out.println("An IO error occurred in Switch " + netID + " talking to master. " +
                        "Stack trace is shown below.");
                e.printStackTrace();
            } finally {
                try{
                    Thread.sleep(2000);
                    master.close();
                } catch (InterruptedException e){
                    System.out.println("Server " + netID + "Unknown system interrupt encountered");
                    e.printStackTrace();
                } catch (IOException e){
                    System.out.println("Server " + netID + "Unknown IO exception; could not close socket to master");
                    e.printStackTrace();
                }
            }
        });

        //start threads. they will exit automatically when every node informs the server it is finished
        central.start();
        acceptor.start();
        manager.start();
    }
}