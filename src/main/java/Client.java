import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.Arrays;

class Client {
    private Socket socket;
    private DatagramPacket datagramPacket;
    private DatagramSocket datagramSocket;

    private byte[] bytes;

    {
        socket = null;
        datagramPacket = null;
        bytes = new byte[2048];
    }

    Client(Socket _socket) {
        this.socket = _socket;
    }


    @Override
    public String toString() {

        return null;
    }

    public void startConnection() {
        try {
            System.out.printf("%s:%s", socket.getLocalAddress(), socket.getLocalPort());
            datagramSocket = new DatagramSocket(socket.getLocalPort(), socket.getLocalAddress());
            datagramPacket = new DatagramPacket(bytes, bytes.length, socket.getLocalAddress(), socket.getLocalPort());
            datagramSocket.receive(datagramPacket);
            System.out.println(Arrays.toString(datagramPacket.getData()));

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void closeConnection() {
        try {
            datagramSocket.close();
            socket.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}