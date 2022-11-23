import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * First-level switch object class
 */

public class Switch extends Thread{
    private final int port, netID;
    private final ArrayList<NodeThread> clients;
    //Format: {network ID, node ID, index for clients arraylist}.
    //In my case, "ports" are logical (arraylist index), not physical, due to Java's native socket implementation.
    private final ArrayList<Integer[]> switchTable;
    //a buffer message queue, because I have no reason to make this a static size data field.  If I am required to
    //do that, I will dispute the point, since nowhere is it implied or explicitly stated that the frame cannot be
    //a dynamic object. This is just the cleanest and easiest way to implement such a thing, despite it being extremely
    //unnecessary with java's implementation of sockets.
    private final LinkedList<Frame> buffer;
    private volatile boolean finished;
    private final boolean debugInfo;

    /**
     * Switch constructor
     * @param port Local listen port (communication port is dynamic per connection)
     * @param netID Network ID corresponding to this switch
     * @param debugInfo enable debug information
     */
    public Switch(int port, int netID, boolean debugInfo){
        this.debugInfo = debugInfo;
        this.netID = netID;
        this.port = port;
        this.clients = new ArrayList<>();
        this.buffer = new LinkedList<>();
        this.switchTable = new ArrayList<>();
    }

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
        } catch (InterruptedException e){
            System.out.println("Unknown system interrupt encountered... retrying...");
            checkFinished();
        }
    }

    @Override
    public void run() {
        //as in project 1, this class contains 2 threads

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
                    while (!finished) {
                        //wait for client connection
                        Socket client = serverSocket.accept();
                        if(debugInfo) System.out.println("Server: New client connected");
                        NodeThread nodeThread = new NodeThread(self, client, debugInfo);
                        nodeThread.start();
                        //add to instance field list
                        synchronized (clients){
                            clients.add(nodeThread);
                        }
                    }
                } catch (SocketException e){
                    if(debugInfo) System.out.println("Server: acceptor thread forced close");
                } catch (IOException e) {
                    System.out.println("Server: unknown IOException encountered");
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
                    if(debugInfo) System.out.println("Server: acceptor thread forced close");
                }
            }
        };
        //Thread 2: Manages buffer and switching messages to correct client
        //this one is a lambda, because it's cleaner, and I only need to implement run() here
        Thread manager = new Thread(() -> {
            while(!finished){
                //skip over until there is data to send
                if(buffer.isEmpty()) {
                    Thread.yield();
                    continue;
                }
                if(debugInfo) System.out.println("Server: message found in buffer");
                Frame message = dequeueMessage();
                //Note that the NodeThread automatically informs Switch of unidentified clients (see addEntry)
                //The switch object therefore adds entries to the switching table in that method automatically
                //That is why adding entries to the switch table is not handled in this block

                //check switch table for sending area
                boolean found = false;
                int key = -1;
                synchronized (switchTable){
                    for(Integer[] entry : switchTable){
                        //look for destination in table
                        if(message.getDest()[1] == entry[0]){
                            //pass along the message
                            if(debugInfo) System.out.println("Server: message passed to communication thread");
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
                if(debugInfo) System.out.println("Server: message will be flooded");
                synchronized (clients){
                    for(int i = 0; i < clients.size(); i++){
                        if(i == key) continue;
                        clients.get(i).newMessage(message);
                    }
                }
            }
            //manager thread is done, which means all data is finished sending.  start cleanup.
            if(debugInfo) System.out.println("Server: communication threads completed, starting cleanup");
            //cleanup: tell all communication threads to finish and close.
            for(NodeThread t: clients){
                t.interrupt();
            }
            //cleanup: force acceptor to close
            if(acceptor.isAlive()) acceptor.interrupt();
        });
        //start threads. they will exit automatically when every node informs the server it is finished
        acceptor.start();
        manager.start();
    }
}