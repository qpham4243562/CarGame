import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class GameServer {
    private static final int PORT = 5000;
    private static final int WIN_SCORE = 500;
    private static final int POINTS_PER_DODGE = 10;
    private static final int POINTS_PER_COLLISION = -5;
    private static final int GAME_WIDTH = 800;
    private static final int GAME_HEIGHT = 600;
    private static final int OBSTACLE_SIZE = 30;

    private final DatagramSocket socket;
    private final List<ClientInfo> clients = new CopyOnWriteArrayList<>();
    private final Map<Integer, Integer> playerScores = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> playerPositions = new ConcurrentHashMap<>();
    private final List<Obstacle> obstacles = new CopyOnWriteArrayList<>();
    private final Random random = new Random();

    private boolean gameStarted = false;
    private boolean gameOver = false;


    public GameServer() throws IOException {
        socket = new DatagramSocket(PORT);
        System.out.println("Server started on port " + PORT);
    }

    // Phương thức chính để khởi động server, gồm 2 luồng (gameLoop và nhận thông điệp từ client)
    public void start() {
        new Thread(this::gameLoop).start();
        new Thread(this::receiveClientMessages).start();
    }

    // Vòng lặp chính của trò chơi, xử lý logic trò chơi, bao gồm việc tạo chướng ngại vật, di chuyển và kiểm tra va chạm
    private void gameLoop() {
        while (true) {
            if (gameStarted && !gameOver) {
                if (random.nextInt(100) < 10) {
                    createObstacle();
                }
                moveObstacles();
                checkCollisions();
                broadcastGameState();
            }

            try {
                Thread.sleep(16);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // Nhận thông điệp từ client và xử lý chúng
    private void receiveClientMessages() {
        while (true) {
            try {
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                handleClientMessage(message, packet.getAddress(), packet.getPort());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Xử lý các thông điệp từ client như kết nối và di chuyển
    private void handleClientMessage(String message, InetAddress address, int port) {
        if (message.startsWith("CONNECT")) {
            handleConnect(address, port);
        } else if (message.startsWith("MOVE")) {
            handleMove(message, port);
        }
    }

    // Xử lý khi một client mới kết nối vào server
    private void handleConnect(InetAddress address, int port) {
        if (clients.size() < 2) {
            ClientInfo newClient = new ClientInfo(address, port);
            clients.add(newClient);
            playerScores.put(port, 0);
            playerPositions.put(port, GAME_WIDTH / 2);
            System.out.println("Player " + clients.size() + " connected.");

            if (clients.size() == 2) {
                gameStarted = true;
                broadcastMessage("START");
            }
        }
    }

    // Xử lý thông tin di chuyển của người chơi
    private void handleMove(String message, int port) {
        String[] parts = message.split(" ");
        int newX = Integer.parseInt(parts[1]);
        playerPositions.put(port, newX);
    }

    // Tạo chướng ngại vật mới ngẫu nhiên xuất hiện từ phía trên
    private void createObstacle() {
        obstacles.add(new Obstacle(random.nextInt(GAME_WIDTH - OBSTACLE_SIZE), 0));
    }

    // Di chuyển các chướng ngại vật từ trên xuống dưới
    private void moveObstacles() {
        for (Obstacle obstacle : obstacles) {
            obstacle.y += 5;
        }
        obstacles.removeIf(obstacle -> obstacle.y > GAME_HEIGHT);
    }

    // Kiểm tra va chạm giữa người chơi và chướng ngại vật
    private void checkCollisions() {
        for (ClientInfo client : clients) {
            int playerX = playerPositions.get(client.port);
            Rectangle playerRect = new Rectangle(playerX, GAME_HEIGHT - MultiplayerGame.PLAYER_HEIGHT - 10, MultiplayerGame.PLAYER_WIDTH, MultiplayerGame.PLAYER_HEIGHT);


            List<Obstacle> obstaclesToRemove = new ArrayList<>();
            for (Obstacle obstacle : obstacles) {
                Rectangle obstacleRect = new Rectangle(obstacle.x, obstacle.y, OBSTACLE_SIZE, OBSTACLE_SIZE); // Hitbox chướng ngại vật

                if (playerRect.intersects(obstacleRect)) {
                    updateScore(client.port, POINTS_PER_COLLISION);
                    updateOpponentScore(client.port, POINTS_PER_DODGE);
                    obstaclesToRemove.add(obstacle);
                }
            }
            obstacles.removeAll(obstaclesToRemove);
        }
    }

    // Cập nhật điểm của đối thủ khi người chơi va chạm với chướng ngại vật
    private void updateOpponentScore(int port, int points) {
        for (ClientInfo client : clients) {
            if (client.port != port) {
                playerScores.merge(client.port, points, Integer::sum);
            }
        }
    }

    // Cập nhật điểm của người chơi, nếu đạt đủ điểm thì kết thúc trò chơi
    private void updateScore(int port, int points) {
        int newScore = playerScores.merge(port, points, Integer::sum);
        if (newScore >= WIN_SCORE) {
            gameOver = true;
            broadcastMessage("GAME_OVER " + port);
            gameStarted = false;
        }
    }

    // Truyền trạng thái trò chơi cho tất cả các client (bao gồm vị trí người chơi, chướng ngại vật và điểm số)
    private void broadcastGameState() {
        StringBuilder state = new StringBuilder("STATE ");
        for (Map.Entry<Integer, Integer> entry : playerPositions.entrySet()) {
            state.append(entry.getKey()).append(":").append(entry.getValue()).append(",");
        }
        state.append(";");
        for (Obstacle obstacle : obstacles) {
            state.append(obstacle.x).append(":").append(obstacle.y).append(",");
        }
        state.append(";");
        for (Map.Entry<Integer, Integer> entry : playerScores.entrySet()) {
            state.append(entry.getKey()).append(":").append(entry.getValue()).append(",");
        }
        broadcastMessage(state.toString());
    }

    // Truyền thông điệp tới tất cả các client
    private void broadcastMessage(String message) {
        byte[] data = message.getBytes();
        for (ClientInfo client : clients) {
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length, client.address, client.port);
                socket.send(packet);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) throws IOException {
        new GameServer().start();
    }

    // Lớp lưu thông tin của client (người chơi)
    private static class ClientInfo {
        final InetAddress address;
        final int port;

        ClientInfo(InetAddress address, int port) {
            this.address = address;
            this.port = port;
        }
    }

    // Lớp đại diện cho chướng ngại vật trong trò chơi
    private static class Obstacle {
        int x, y;

        Obstacle(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    // Lớp đại diện cho hình chữ nhật (hitbox) để kiểm tra va chạm
    private static class Rectangle {
        int x, y, width, height;

        Rectangle(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        // Hàm kiểm tra nếu có sự giao nhau giữa hai hình chữ nhật (va chạm)
        boolean intersects(Rectangle other) {
            return this.x < other.x + other.width &&
                    this.x + this.width > other.x &&
                    this.y < other.y + other.height &&
                    this.y + this.height > other.y;
        }
    }
}
