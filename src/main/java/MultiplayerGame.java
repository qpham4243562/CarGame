import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class MultiplayerGame extends JFrame {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int PLAYER_WIDTH = 50;
    private static final int PLAYER_HEIGHT = 100;
    private static final int OBSTACLE_SIZE = 30;

    private int playerX = WIDTH / 2 - PLAYER_WIDTH / 2;
    private int opponentX = WIDTH / 2 - PLAYER_WIDTH / 2;
    private CopyOnWriteArrayList<Point> obstacles = new CopyOnWriteArrayList<>();  // Thread-safe list
    private boolean gameStarted = false;
    private int playerScore = 0;
    private int opponentScore = 0;
    private DatagramSocket socket;
    private InetAddress serverAddress;
    private int serverPort = 5000;

    // For smoother movement
    private boolean moveLeft = false;
    private boolean moveRight = false;

    public MultiplayerGame() {
        setTitle("Falling Obstacles Game");
        setSize(WIDTH, HEIGHT);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

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

        connectToServer();

        Timer gameTimer = new Timer(16, e -> {
            if (gameStarted) {
                if (moveLeft && playerX > 0) {
                    playerX -= 5;
                } else if (moveRight && playerX < WIDTH - PLAYER_WIDTH) {
                    playerX += 5;
                }
                sendPosition();
                repaint();
            }
        });
        gameTimer.start();
    }

    private void connectToServer() {
        try {
            socket = new DatagramSocket();
            serverAddress = InetAddress.getByName("26.178.54.215");
            sendMessage("CONNECT");
            new Thread(this::receiveServerMessages).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void receiveServerMessages() {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (true) {
            try {
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                handleServerMessage(message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleServerMessage(String message) {
        if (message.equals("START")) {
            gameStarted = true;
        } else if (message.startsWith("STATE")) {
            updateGameState(message);
        } else if (message.startsWith("GAME_OVER")) {
            handleGameOver(message);
            gameStarted = false;  // Dừng trò chơi
        }
    }


    private void updateGameState(String state) {
        String[] parts = state.substring(6).split(";");  // Remove "STATE " prefix
        if (parts.length == 3) {
            updatePlayerPositions(parts[0]);
            updateObstacles(parts[1]);
            updateScores(parts[2]);
        } else {
            System.out.println("Invalid game state format: " + state);
        }
    }

    private void updatePlayerPositions(String positionsData) {
        String[] positions = positionsData.split(",");
        for (String position : positions) {
            String[] data = position.split(":");
            if (data.length == 2) {
                int port = Integer.parseInt(data[0]);
                int x = Integer.parseInt(data[1]);
                if (port == socket.getLocalPort()) {
                    playerX = x;
                } else {
                    opponentX = x;
                }
            }
        }
    }

    private void updateObstacles(String obstaclesData) {
        obstacles.clear();
        String[] obstacleList = obstaclesData.split(",");
        for (String obs : obstacleList) {
            String[] coords = obs.split(":");
            if (coords.length == 2) {
                int x = Integer.parseInt(coords[0]);
                int y = Integer.parseInt(coords[1]);
                obstacles.add(new Point(x, y));
            }
        }
    }

    private void updateScores(String scoresData) {
        String[] scores = scoresData.split(",");
        for (String score : scores) {
            String[] data = score.split(":");
            if (data.length == 2) {
                int port = Integer.parseInt(data[0]);
                int value = Integer.parseInt(data[1]);
                if (port == socket.getLocalPort()) {
                    playerScore = value;
                } else {
                    opponentScore = value;
                }
            }
        }
    }

    private void handleGameOver(String message) {
        String[] parts = message.split(" ");
        int winnerPort = Integer.parseInt(parts[1]);
        String winnerMessage = (winnerPort == socket.getLocalPort()) ? "You win!" : "Opponent wins!";
        JOptionPane.showMessageDialog(this, winnerMessage, "Game Over", JOptionPane.INFORMATION_MESSAGE);

        // Ngừng trò chơi
        gameStarted = false;
        moveLeft = false;
        moveRight = false;
    }


    private void sendPosition() {
        sendMessage("MOVE " + playerX);
    }

    private void sendMessage(String message) {
        byte[] data = message.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, serverPort);
        try {
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        if (!gameStarted) {
            g.setColor(Color.BLACK);
            g.setFont(new Font("Arial", Font.BOLD, 20));
            g.drawString("Waiting for another player...", WIDTH / 2 - 120, HEIGHT / 2);
            return;
        }

        // Draw players
        g.setColor(Color.BLUE);
        g.fillRect(playerX, HEIGHT - PLAYER_HEIGHT - 10, PLAYER_WIDTH, PLAYER_HEIGHT);
        g.setColor(Color.RED);
        g.fillRect(opponentX, HEIGHT - PLAYER_HEIGHT - 10, PLAYER_WIDTH, PLAYER_HEIGHT);

        // Draw obstacles
        g.setColor(Color.GREEN);
        for (Point obstacle : obstacles) {
            g.fillRect(obstacle.x, obstacle.y, OBSTACLE_SIZE, OBSTACLE_SIZE);
        }

        // Draw scores
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 20));
        g.drawString("Your Score: " + playerScore, 10, 50);

        // Measure the width of the opponent's score text
        String opponentScoreText = "Opponent's Score: " + opponentScore;
        FontMetrics metrics = g.getFontMetrics();
        int textWidth = metrics.stringWidth(opponentScoreText);

        // Calculate X position to draw the opponent's score on the right side
        int opponentScoreXPos = WIDTH - textWidth - 10;  // 10px padding from the right edge

        // Draw opponent's score
        g.drawString(opponentScoreText, opponentScoreXPos, 50);
    }


    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MultiplayerGame().setVisible(true));
    }
}
