import java.io.IOException;
import java.net.Socket;

public class Connection {
    public static void main(String[] args) {
        try {
            Client client = new Client(new Socket("127.0.0.1", 8888));
            client.startConnection();
            client.closeConnection();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
