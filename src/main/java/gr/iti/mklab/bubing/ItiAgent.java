package gr.iti.mklab.bubing;

import gr.iti.mklab.image.VisualIndexer;
import it.unimi.di.law.bubing.Agent;

/**
 * Created by kandreadou on 12/9/14.
 */
public class ItiAgent {

    public static void main( final String arg[] ) throws Exception {
        VisualIndexer.getInstance();
        Agent.main(arg);
    }
}
