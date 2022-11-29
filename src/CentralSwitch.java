import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Scanner;

/**
 * Global level switch thread
 */
public class CentralSwitch extends Thread{
    private final int port;
    private final ArrayList<SwitchThread> clients;
    //each entry in the list is a firewalled network ID
    private final ArrayList<Integer> firewall;
    //Format: {network ID, index for clients arraylist}.
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
     * @param debugInfo enable debug information
     */
    public CentralSwitch(int port, boolean debugInfo){
        this.debugInfo = debugInfo;
        this.port = port;
        this.firewall = new ArrayList<>();
        this.clients = new ArrayList<>();
        this.buffer = new LinkedList<>();
        this.switchTable = new ArrayList<>();
        //Initialize data from file
        try{
            //Set scanner to read config file
            Scanner scanner = new Scanner(new File("firewall.txt"));
            while (scanner.hasNextLine()){
                //get data line
                String s = scanner.nextLine();
                //pull out node info
                String[] d = s.split(":")[0].split("_");
                int n = Integer.parseInt(d[0]);
                //if global firewall rule, add it to the field
                if(d[1].equals("#")) firewall.add(n);
                //if local firewall rule (ie, specific node), add a control frame to the buffer queue
                else buffer.add(new Frame(-1, -1, n, 0, 0, d[1]));
            }
            //control messages of this sort are by definition flooded to all switches.
            buffer.add(new Frame(-1, -1, 0, 0, 0, 1));
            scanner.close();
            if(debugInfo) System.out.println("Master: firewall successfully loaded.");
        } catch(FileNotFoundException e){
            System.out.println("Master: An error occurred loading input file: FileNotFoundException\n");
            e.printStackTrace();
        }
        if(debugInfo) System.out.println("Master: firewall " + firewall);
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
            for(SwitchThread thread: this.clients){
                if(!thread.finished()) return;
            }
            //sleep for 1 second
            Thread.sleep(1000);
            //check again
            for(SwitchThread thread: this.clients){
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
        CentralSwitch self = this;
        //Thread 1: accepts incoming connections, creates NodeThreads to handle communication.
        //this is an abstract class, as I have to override interrupt as well as run to terminate it on cleanup.
        Thread acceptor = new Thread(){
            private ServerSocket serverSocket;
            @Override
            public void run() {
                //set up server socket
                try {
                    if(debugInfo) System.out.println("Master: creating server on port " + port);
                    serverSocket = new ServerSocket(port);
                    //deadloop -- listen for client connection and handle it
                    while (!finished) {
                        //wait for client connection
                        Socket client = serverSocket.accept();
                        if(debugInfo) System.out.println("Master: New client connected");
                        SwitchThread switchThread = new SwitchThread(self, client, debugInfo);
                        switchThread.start();
                        //add to instance field list
                        synchronized (clients){
                            clients.add(switchThread);
                        }
                    }
                } catch (SocketException e){
                    if(debugInfo) System.out.println("Master: acceptor thread forced close");
                } catch (IOException e) {
                    System.out.println("Master: unknown IOException encountered");
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
                    if(debugInfo) System.out.println("Master: acceptor thread forced close");
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
                Frame message = dequeueMessage();
                if(debugInfo) System.out.println("Master: message found in buffer " + message);
                //Note that the SwitchThread automatically informs Switch of unidentified clients (see addEntry)
                //The switch object therefore adds entries to the switching table in that method automatically
                //That is why adding entries to the switch table is not handled in this block

                //check for firewall; if local node is firewalled, nack
                for(int i: firewall){
                    if(message.getDest()[0] == i){
                        //firewall found
                        //acks need to pass through the firewall
                        if(message.getSize() == 0) break;
                        //not an ack message, so replace it with a nack back to the source.
                        if(debugInfo) System.out.println("Master: message firewalled. bouncing nack." + message);
                        message = new Frame(i, message.getDest()[1], message.getSource()[0], message.getSource()[1],
                                message.getSN(), 4);
                        break;
                    }
                }

                //check switch table for sending area -- because of firewall data packets, all networks are guaranteed to be identified
                boolean found = false;
                int key = -1;
                synchronized (switchTable){
                    for(Integer[] entry : switchTable){
                        //look for destination in table
                        if(message.getDest()[0] == entry[0]){
                            //pass along the message
                            if(debugInfo) System.out.println("Master: message passed to communication thread" + message);
                            clients.get(entry[1]).newMessage(message);
                            found = true;
                            break;
                        }
                        //this is for next block for flooding purposes; it isn't used if dest is present in the table
                        if(message.getSource()[0] == entry[0]) key = entry[1];
                    }
                }
                if(found) continue;
                //this block will only be reached if the target not found in switch table, so here we flood
                if(debugInfo) System.out.println("Master: message will be flooded " + message);
                synchronized (clients){
                    for(int i = 0; i < clients.size(); i++){
                        if(i == key) continue;
                        clients.get(i).newMessage(message);
                    }
                }
            }
            //manager thread is done, which means all data is finished sending.  start cleanup.
            if(debugInfo) System.out.println("Master: communication threads completed, starting cleanup");
            //cleanup: tell all communication threads to finish and close.
            for(SwitchThread t: clients){
                t.interrupt();
            }
            //cleanup: force acceptor to close
            if(acceptor.isAlive()) acceptor.interrupt();
        });
        //start threads. they will exit automatically when every node informs the server it is finished
        acceptor.start();
        //give time for acceptors to connect
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            System.out.println("Master: Unknown interruption encountered at startup");
            throw new RuntimeException(e);
        }
        //start manager
        manager.start();
    }
}