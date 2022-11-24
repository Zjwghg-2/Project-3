import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

public class Main {

    /**
     * "Script" to generate files (called automatically by main)
     * @param index index into list
     * @param list List of nodes
     */
    public static void makeFile(int index, ArrayList<int[]> list){
        File f;
        FileWriter writer;
        try{
            //references
            int net = list.get(index)[0];
            int node = list.get(index)[1];
            //create and open file
            f = new File("node"+net+"_"+node+".txt");
            f.createNewFile();
            writer = new FileWriter(f.getName());
            Random generator = new Random();
            //generate data; this will be from [1,size-1] -- every node will send at least 1 packet, and will not send
            //more packets than there are other nodes.
            int[] outs = new int[generator.nextInt(list.size()-1)+1];
            boolean flag;
            for(int i = 0; i < outs.length; i++){
                flag = true;
                while(flag){
                    //output node range is [1,size] -- this is fine since the number will re-roll if sending to itself.
                    //outs is an array of indexes into list
                    outs[i] = generator.nextInt(list.size());
                    //ensure node won't send data to itself
                    if(i == outs[i]) continue;
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
                int tNet = list.get(out)[0];
                int tNode = list.get(out)[1];
                writer.write(tNet + "_" + tNode + ": To node " + tNet + "_" + tNode + "; " + generator.nextLong() + "\n");
                writer.flush();
            }
        } catch (IOException e){
            System.out.println("Could not create / write to file of node number: " + index);
            e.printStackTrace();
        }
    }

    /**
     * Helper function generates list of nodes
     * @param maxSwitch Number of switches
     * @param maxNode Number of nodes
     * @return ArrayList of form {netID, nodeID}
     */
    public static ArrayList<int[]> makeList(int maxSwitch, int maxNode){
        ArrayList<int[]> ret = new ArrayList<>();
        int[] switches = new int[maxSwitch];
        Random generator = new Random();
        int net;
        //this loop gives 1 node to every switch -- ensures each switch communicates with *something* at least
        int x;
        for(x = 0; x < maxSwitch; x++){
            switches[x] = 1;
        }
        //this loop fills in the rest, randomly. The result is an array of number of nodes for each switch.
        for(int i = x; i < maxNode; i++){
            //pick a random switch
            net = generator.nextInt(maxSwitch);
            //give it a new node
            switches[net]++;
            //check for duplicate
        }
        //this loop generates an ArrayList with ID for each node
        for(int i = 0; i < maxSwitch; i++){
            int per = switches[i];
            //create the number of nodes as specified by switches[i], add it to the return
            for(int j = 1; j <= per; j++){
                ret.add(new int[]{i+1,j});
            }
        }
        return ret;
    }


    public static void main(String[] args){
        /*if(args.length != 1){
            System.out.println("Use: java Main [number of nodes]");
            return;
        }*/
        //Variable controls
        int port = 1234;
        int masterPort = 4321;
        boolean nodeDebugInfo = true;
        boolean serverDebugInfo = true;
        /*get number of nodes
        int max = Integer.parseInt(args[0]);
        if(max == 1){
            System.out.println("Please use more than 1 node");
            return;
        }*/
        //make list
        int maxSwitch = 1;
        int maxNode = 5;
        ArrayList<int[]> list = makeList(maxSwitch, maxNode);
        //make files
        for(int i = 0; i < list.size(); i++){
            makeFile(i, list);
        }
        //make switches
        Switch[] switches = new Switch[maxSwitch];
        for(int i = 0; i < maxSwitch; i++){
            switches[i] = new Switch(port+i, i+1, masterPort, serverDebugInfo);
        }
        //make nodes
        Node[] nodes = new Node[maxNode];
        for(int i = 0; i < list.size(); i++){
            int nnet = list.get(i)[0];
            int nid = list.get(i)[1];
            nodes[i] = new Node(port+nnet-1, nid, nnet, nodeDebugInfo);
        }
        //start threads
        for(Thread s: switches){
            s.start();
        }
        for(Thread n : nodes){
            n.start();
        }
        //wait for them all to finish
        boolean done = false;
        while(!done){
            done = true;
            //check server status
            for(Switch s: switches){
                if(s.isAlive()){
                    done = false;
                    break;
                }
            }
            //check node statuses
            for(Node n: nodes){
                if(n.isAlive()){
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