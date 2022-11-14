import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;

public class Main {

    /**
     * "Script" to generate files (called automatically by main)
     * @param node current node value
     * @param max total number of nodes
     */
    public static void makeFile(int node, int max){
        File f;
        FileWriter writer;
        try{
            //create and open file
            f = new File("node"+node+".txt");
            f.createNewFile();
            writer = new FileWriter(f.getName());
            Random generator = new Random();
            //generate data; this will be from [1,max-1] -- every node will send at least 1 packet, and will not send
            //more packets than there are other nodes.
            int[] outs = new int[generator.nextInt(max-1)+1];
            boolean flag;
            for(int i = 0; i < outs.length; i++){
                flag = true;
                while(flag){
                    //output node range is [1,max] -- this is fine since the number will re-roll if sending to itself.
                    outs[i] = generator.nextInt(max)+1;
                    //ensure node won't send data to itself
                    if(outs[i] == node) continue;
                    //for simplicity, ensure node will only send 1 package to any given other node
                    for(int j = 0; j < i; j++){
                        if(outs[j] == outs[i]){
                            flag = false;
                            break;
                        }
                    }
                    //reverse flag. if it was set as false in the for loop, it's now true again and loop goes again
                    //if it was not set, flag is changed to false and the loop ends.
                    flag = !flag;
                }
            }
            //now write data
            for (int out : outs) {
                //write data; data will be of the same format, with a random long for variance.
                writer.write(out + ": To node " + out + "; " + generator.nextLong() + "\n");
                writer.flush();
            }
        } catch (IOException e){
            System.out.println("Could not create / write to file of node number: " + node);
            e.printStackTrace();
        }
    }


    public static void main(String[] args){
        if(args.length != 1){
            System.out.println("Use: java Main [number of nodes]");
            return;
        }
        //Variable controls
        int port = 1234;
        boolean nodeDebugInfo = false;
        boolean serverDebugInfo = false;
        //get number of nodes
        int max = Integer.parseInt(args[0]);
        if(max == 1){
            System.out.println("Please use more than 1 node");
            return;
        }
        //generate files
        for(int i = 1; i <= max; i++){
            makeFile(i, max);
        }
        //make switch
        Thread server = new Thread(new Switch(port, serverDebugInfo));
        //make nodes
        Node[] nodes = new Node[max];
        for(int i = 0; i < max; i++){
            nodes[i] = new Node(port, i+1, nodeDebugInfo);
        }
        //start threads
        server.start();
        for(Thread n : nodes){
            n.start();
        }
        //wait for them all to finish
        boolean done = false;
        while(!done){
            done = true;
            //check server status
            if(server.isAlive()){
                done = false;
                continue;
            }
            //check node statuses
            for(Node node: nodes){
                if(node.isAlive()){
                    done = false;
                    break;
                }
            }
        }
        //inform of completion
        try {
            //sleep just to make sure main prints stuff after everything else, to make it nicer to read
            //all the objects handle cleanup themselves, but because of that they might still be running after
            Thread.sleep(150);
            System.out.println("-------------------------------------------------");
            System.out.println("All threads have finished.");
            System.out.println("-------------------------------------------------");
        } catch (InterruptedException e){
            System.out.println("There was an unknown error in main but all the threads still finished");
        }
    }
}