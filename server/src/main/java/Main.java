import com.google.inject.Guice;
import com.google.inject.Injector;
import guice.GuiceModule;
import server.ServerServices;

public class Main {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Too few arguments, the correct syntax is : <ip address> <port> ...");
            System.out.println("321");
            System.exit(1);
        }

        Injector injector = Guice.createInjector(new GuiceModule());

        final String host = args[0];
        final int port = Integer.parseInt(args[1]);

        ServerServices server = injector.getInstance(ServerServices.class);
        server.open(host, port);
    }
}
