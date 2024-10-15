import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class MultiplayerGame extends JFrame {
    public static final int WIDTH = 800;
    public static final int HEIGHT = 600;
    public static final int PLAYER_WIDTH = 50;
    public static final int PLAYER_HEIGHT = 100;
    public static final int OBSTACLE_SIZE = 30;

    public int playerX = WIDTH / 2 - PLAYER_WIDTH / 2;
    public int opponentX = WIDTH / 2 - PLAYER_WIDTH / 2;
    public CopyOnWriteArrayList<Point> obstacles = new CopyOnWriteArrayList<>();  // Thread-safe list
    public boolean gameStarted = false;
    public int playerScore = 0;
    public int opponentScore = 0;
    public DatagramSocket socket;
    public InetAddress serverAddress;
    public int serverPort = 5000;

    // Điều khiển di chuyển mượt hơn
    private boolean moveLeft = false;
    private boolean moveRight = false;

    // Constructor: Khởi tạo giao diện trò chơi và kết nối với server
    public MultiplayerGame() {
        setTitle("Falling Obstacles Game");
        setSize(WIDTH, HEIGHT);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        // Lắng nghe sự kiện bàn phím để di chuyển người chơi
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_LEFT) {
                    moveLeft = true;
                } else if (e.getKeyCode() == KeyEvent.VK_RIGHT) {
                    moveRight = true;
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_LEFT) {
                    moveLeft = false;
                } else if (e.getKeyCode() == KeyEvent.VK_RIGHT) {
                    moveRight = false;
                }
            }
        });

        connectToServer(); // Kết nối tới server

        // Tạo bộ hẹn giờ cho game để cập nhật và vẽ lại khung hình sau mỗi 16ms
        Timer gameTimer = new Timer(16, e -> {
            if (gameStarted) {
                if (moveLeft && playerX > 0) {
                    playerX -= 5;
                } else if (moveRight && playerX < WIDTH - PLAYER_WIDTH) {
                    playerX += 5;
                }
                sendPosition();  // Gửi vị trí người chơi cho server
                repaint();  // Vẽ lại giao diện game
            }
        });
        gameTimer.start();
    }

    // Kết nối tới server bằng giao thức UDP
    private void connectToServer() {
        try {
            socket = new DatagramSocket();
            serverAddress = InetAddress.getByName("172.20.10.2");
            sendMessage("CONNECT");  // Gửi thông điệp yêu cầu kết nối
            new Thread(this::receiveServerMessages).start();  // Tạo luồng nhận tin nhắn từ server
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Nhận thông điệp từ server và xử lý chúng
    private void receiveServerMessages() {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (true) {
            try {
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                handleServerMessage(message);  // Xử lý thông điệp từ server
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Xử lý thông điệp từ server như bắt đầu trò chơi, cập nhật trạng thái hoặc kết thúc trò chơi
    private void handleServerMessage(String message) {
        if (message.equals("START")) {
            gameStarted = true;  // Trò chơi bắt đầu
        } else if (message.startsWith("STATE")) {
            updateGameState(message);  // Cập nhật trạng thái trò chơi
        } else if (message.startsWith("GAME_OVER")) {
            handleGameOver(message);  // Xử lý kết thúc trò chơi
            gameStarted = false;  // Dừng trò chơi
        }
    }

    // Cập nhật trạng thái trò chơi từ server, bao gồm vị trí, chướng ngại vật, và điểm số
    private void updateGameState(String state) {
        String[] parts = state.substring(6).split(";");  // Loại bỏ tiền tố "STATE "
        if (parts.length == 3) {
            updatePlayerPositions(parts[0]);  // Cập nhật vị trí người chơi
            updateObstacles(parts[1]);  // Cập nhật vị trí chướng ngại vật
            updateScores(parts[2]);  // Cập nhật điểm số
        } else {
            System.out.println("Invalid game state format: " + state);
        }
    }

    // Cập nhật vị trí của người chơi và đối thủ
    private void updatePlayerPositions(String positionsData) {
        String[] positions = positionsData.split(",");
        for (String position : positions) {
            String[] data = position.split(":");
            if (data.length == 2) {
                int port = Integer.parseInt(data[0]);
                int x = Integer.parseInt(data[1]);
                if (port == socket.getLocalPort()) {
                    playerX = x;  // Cập nhật vị trí người chơi hiện tại
                } else {
                    opponentX = x;  // Cập nhật vị trí đối thủ
                }
            }
        }
    }

    // Cập nhật danh sách chướng ngại vật từ server
    private void updateObstacles(String obstaclesData) {
        obstacles.clear();
        String[] obstacleList = obstaclesData.split(",");
        for (String obs : obstacleList) {
            String[] coords = obs.split(":");
            if (coords.length == 2) {
                int x = Integer.parseInt(coords[0]);
                int y = Integer.parseInt(coords[1]);
                obstacles.add(new Point(x, y));  // Thêm chướng ngại vật vào danh sách
            }
        }
    }

    // Cập nhật điểm số của người chơi và đối thủ từ server
    private void updateScores(String scoresData) {
        String[] scores = scoresData.split(",");
        for (String score : scores) {
            String[] data = score.split(":");
            if (data.length == 2) {
                int port = Integer.parseInt(data[0]);
                int value = Integer.parseInt(data[1]);
                if (port == socket.getLocalPort()) {
                    playerScore = value;  // Cập nhật điểm số của người chơi
                } else {
                    opponentScore = value;  // Cập nhật điểm số của đối thủ
                }
            }
        }
    }

    // Xử lý kết thúc trò chơi khi nhận được thông điệp từ server
    private void handleGameOver(String message) {
        String[] parts = message.split(" ");
        int winnerPort = Integer.parseInt(parts[1]);
        String winnerMessage = (winnerPort == socket.getLocalPort()) ? "You win!" : "Opponent wins!";
        JOptionPane.showMessageDialog(this, winnerMessage, "Game Over", JOptionPane.INFORMATION_MESSAGE);

        // Dừng trò chơi và đặt lại trạng thái điều khiển
        gameStarted = false;
        moveLeft = false;
        moveRight = false;
    }

    // Gửi vị trí người chơi tới server
    private void sendPosition() {
        sendMessage("MOVE " + playerX);
    }

    // Gửi thông điệp tới server
    private void sendMessage(String message) {
        byte[] data = message.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, serverPort);
        try {
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Hàm vẽ giao diện trò chơi, hiển thị người chơi, đối thủ, chướng ngại vật và điểm số
    @Override
    public void paint(Graphics g) {
        super.paint(g);
        if (!gameStarted) {
            g.setColor(Color.BLACK);
            g.setFont(new Font("Arial", Font.BOLD, 20));
            g.drawString("Waiting for another player...", WIDTH / 2 - 120, HEIGHT / 2);
            return;
        }

        // Vẽ người chơi
        g.setColor(Color.BLUE);
        g.fillRect(playerX, HEIGHT - PLAYER_HEIGHT - 10, PLAYER_WIDTH, PLAYER_HEIGHT);

        // Vẽ đối thủ
        g.setColor(Color.RED);
        g.fillRect(opponentX, HEIGHT - PLAYER_HEIGHT - 10, PLAYER_WIDTH, PLAYER_HEIGHT);

        // Vẽ chướng ngại vật
        g.setColor(Color.GREEN);
        for (Point obstacle : obstacles) {
            g.fillRect(obstacle.x, obstacle.y, OBSTACLE_SIZE, OBSTACLE_SIZE);
        }

        // Vẽ điểm số của người chơi
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 20));
        g.drawString("Your Score: " + playerScore, 10, 50);

        // Đo lường chiều rộng của văn bản điểm số đối thủ
        String opponentScoreText = "Opponent's Score: " + opponentScore;
        FontMetrics metrics = g.getFontMetrics();
        int textWidth = metrics.stringWidth(opponentScoreText);

        // Vẽ điểm số của đối thủ ở bên phải màn hình
        int opponentScoreXPos = WIDTH - textWidth - 10;  // Khoảng cách 10px từ cạnh phải
        g.drawString(opponentScoreText, opponentScoreXPos, 50);
    }

    // Chạy trò chơi
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MultiplayerGame().setVisible(true));
    }
}
