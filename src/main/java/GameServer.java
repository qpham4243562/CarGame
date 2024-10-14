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
    private boolean gameOver = false;  // Biến kiểm soát trạng thái kết thúc trò chơi


    public GameServer() throws IOException {
        socket = new DatagramSocket(PORT);
        System.out.println("Server started on port " + PORT);
    }

    public void start() {
        new Thread(this::gameLoop).start();
        new Thread(this::receiveClientMessages).start();
    }

    private void gameLoop() {
        while (true) {
            if (gameStarted && !gameOver) {  // Kiểm tra nếu trò chơi đang diễn ra và chưa kết thúc
                if (random.nextInt(100) < 5) {
                    createObstacle();
                }
                moveObstacles();
                checkCollisions();
                broadcastGameState();
            }

            try {
                Thread.sleep(16);  // Giữ tốc độ game khoảng 60 FPS
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


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

    private void handleClientMessage(String message, InetAddress address, int port) {
        if (message.startsWith("CONNECT")) {
            handleConnect(address, port);
        } else if (message.startsWith("MOVE")) {
            handleMove(message, port);
        }
    }

    private void handleConnect(InetAddress address, int port) {
        if (clients.size() < 2) {
            ClientInfo newClient = new ClientInfo(address, port);
            clients.add(newClient);
            playerScores.put(port, 0);
            playerPositions.put(port, GAME_WIDTH / 2);  // Start players in the middle
            System.out.println("Player " + clients.size() + " connected.");

            if (clients.size() == 2) {
                gameStarted = true;
                broadcastMessage("START");
            }
        }
    }

    private void handleMove(String message, int port) {
        String[] parts = message.split(" ");
        int newX = Integer.parseInt(parts[1]);
        playerPositions.put(port, newX);
    }

    private void createObstacle() {
        obstacles.add(new Obstacle(random.nextInt(GAME_WIDTH - OBSTACLE_SIZE), 0));
    }

    private void moveObstacles() {
        for (Obstacle obstacle : obstacles) {
            obstacle.y += 5;
        }
        obstacles.removeIf(obstacle -> obstacle.y > GAME_HEIGHT);  // Remove obstacles that leave the screen
    }

    private void checkCollisions() {
        for (ClientInfo client : clients) {
            int playerX = playerPositions.get(client.port);
            Rectangle playerRect = new Rectangle(playerX, GAME_HEIGHT - 40, 50, 20);

            List<Obstacle> obstaclesToRemove = new ArrayList<>();
            for (Obstacle obstacle : obstacles) {
                Rectangle obstacleRect = new Rectangle(obstacle.x, obstacle.y, OBSTACLE_SIZE, OBSTACLE_SIZE);

                if (playerRect.intersects(obstacleRect)) {
                    // Collision detected - this player loses points, opponent gains points
                    updateScore(client.port, POINTS_PER_COLLISION);
                    updateOpponentScore(client.port, POINTS_PER_DODGE);  // Opponent gains points
                    obstaclesToRemove.add(obstacle);
                }
            }
            obstacles.removeAll(obstaclesToRemove);
        }
    }

    private void updateOpponentScore(int port, int points) {
        for (ClientInfo client : clients) {
            if (client.port != port) {
                playerScores.merge(client.port, points, Integer::sum);  // Opponent gains points
            }
        }
    }


    private void updateScore(int port, int points) {
        int newScore = playerScores.merge(port, points, Integer::sum);
        if (newScore >= WIN_SCORE) {
            // Ngừng trò chơi ngay khi đạt điểm WIN_SCORE
            gameOver = true;  // Biến kiểm soát kết thúc trò chơi
            broadcastMessage("GAME_OVER " + port);
            gameStarted = false;
        }
    }



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

    private static class ClientInfo {
        final InetAddress address;
        final int port;

        ClientInfo(InetAddress address, int port) {
            this.address = address;
            this.port = port;
        }
    }

    private static class Obstacle {
        int x, y;

        Obstacle(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class Rectangle {
        int x, y, width, height;

        Rectangle(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        boolean intersects(Rectangle other) {
            return this.x < other.x + other.width &&
                    this.x + this.width > other.x &&
                    this.y < other.y + other.height &&
                    this.y + this.height > other.y;
        }
    }
}
