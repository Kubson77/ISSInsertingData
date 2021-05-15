package dao;

import model.IssPosition;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import static java.lang.Math.*;

public class Dao implements IDao {

    private static final Logger log;

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%4$-7s] %5$s %n");
        log = Logger.getLogger(Dao.class.getName());
    }

    private final String queryGetLastCoordinates = "SELECT TOP 1 * FROM iss_position ORDER BY id DESC";
    private final String queryGetOneLastCoordinates = "SELECT TOP 1 * FROM (SELECT TOP 2 * FROM iss_position ORDER BY id DESC) iss_position ORDER BY id";
    private URL url;
    private Connection connection;

    {
        try {
            url = new URL("http://api.open-notify.org/iss-now.json");
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int processingStart() {
        IssPosition lastPosition;
        IssPosition oneBeforeLastPosition;
        int speed;
        int position;

        try {
            openConnection();
            if (checkTable()) {
                addIssPosition(collectData());
                lastPosition = getIssData(queryGetLastCoordinates);
                oneBeforeLastPosition = getIssData(queryGetOneLastCoordinates);
                if (checkTimeDifference(lastPosition, oneBeforeLastPosition)) {
                    speed = IssSpeed(lastPosition, oneBeforeLastPosition);
                    addIssSpeed(speed, lastPosition.getId());
                } else {
                    try {
                        Thread.sleep(20_000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    addIssPosition(collectData());
                    lastPosition = getIssData(queryGetLastCoordinates);
                    oneBeforeLastPosition = getIssData(queryGetOneLastCoordinates);
                    speed = IssSpeed(lastPosition, oneBeforeLastPosition);
                    position = lastPosition.getId();
                    addIssSpeed(speed, position, position - 1);
                }
            } else {
                addIssPosition(collectData());
                try {
                    Thread.sleep(20_000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                addIssPosition(collectData());
                lastPosition = getIssData(queryGetLastCoordinates);
                oneBeforeLastPosition = getIssData(queryGetOneLastCoordinates);
                speed = IssSpeed(lastPosition, oneBeforeLastPosition);
                position = lastPosition.getId();
                addIssSpeed(speed, position, position - 1);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        } finally {
            try {
                closeConnection();
            } catch (SQLException throwables) {
                throwables.printStackTrace();
            }
        }

        return 0;
    }

    private void addIssPosition(IssPosition position) {
        String timeInMilis = String.format("%d000", position.getUnixTime());
        Long timeConvert = Long.valueOf(timeInMilis);
        Date date = new Date(timeConvert);

        String insertIssPosition = String.format("INSERT INTO iss_position (latitude, longitude, timestamp, date, time) VALUES('%s', '%s', '%d', '%tF', '%<tT')",
                position.getLatitude(), position.getLongitude(), position.getUnixTime(), date);

        update(insertIssPosition);
    }


    private void addIssSpeed(int speed, int position) {
        String addSpeed = String.format("UPDATE iss_position SET speed = %d WHERE id = %d", speed, position);

        update(addSpeed);
    }

    private void addIssSpeed(int speed, int position1, int position2) {
        String addSpeed = String.format("UPDATE iss_position SET speed = %d WHERE id = %d OR id = %d", speed, position1, position2);

        update(addSpeed);
    }

    private int IssSpeed(IssPosition lastPosition, IssPosition oneBeforeLastPosition) {
        long differenceBetweenTimeStamps =
                lastPosition.getUnixTime()
                        - oneBeforeLastPosition.getUnixTime();

        double x1 = Double.parseDouble(oneBeforeLastPosition.getLatitude());
        double x2 = Double.parseDouble(lastPosition.getLatitude());
        double y1 = Double.parseDouble(oneBeforeLastPosition.getLongitude());
        double y2 = Double.parseDouble(lastPosition.getLongitude());

        double distance = sqrt(pow((x2 - x1), 2) + pow(((cos(x1 * PI / 180)) * (y2 - y1)), 2)) * (40075.704 / 360);
        double time = (double) differenceBetweenTimeStamps / 3600;

        return (int) (distance / time);
    }


    private int update(String input) {

        int result = 1;
        try {
            Statement statement = connection.createStatement();
            result = statement.executeUpdate(input);
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }

        return result;
    }

    private IssPosition getIssData(String query) {
        IssPosition issPosition = new IssPosition();

        try {
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(query);
            while (resultSet.next()) {
                issPosition.setId(resultSet.getInt("id"));
                issPosition.setLongitude(resultSet.getString("longitude"));
                issPosition.setLatitude(resultSet.getString("latitude"));
                issPosition.setUnixTime(resultSet.getLong("timestamp"));
                issPosition.setSpeed(resultSet.getInt("speed"));
            }
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }

        return issPosition;
    }

    private IssPosition collectData() {
        IssPosition issPosition = new IssPosition();

        URLConnection urlcon = null;
        try {
            urlcon = url.openConnection();
        } catch (IOException e) {
            e.printStackTrace();
        }

        InputStream input = null;
        try {
            input = urlcon.getInputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }

        BufferedReader buffer = new BufferedReader(new InputStreamReader(input));

        JSONObject location = null;
        try {
            location = (JSONObject) new JSONParser().parse(buffer);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
        }

        issPosition.setUnixTime((long) location.get("timestamp"));
        Map coordinates = (Map) location.get("iss_position");
        issPosition.setLatitude((String) coordinates.get("latitude"));
        issPosition.setLongitude((String) coordinates.get("longitude"));

        return issPosition;
    }

    private boolean checkTable() {
        boolean check = true;

        try {
            PreparedStatement readStatement = connection.prepareStatement(queryGetLastCoordinates);
            ResultSet resultSet = readStatement.executeQuery();
            if (!resultSet.next()) {
                log.info("There is no data in the database!");
                check = false;
            }
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }

        return check;
    }

    private boolean checkTimeDifference(IssPosition lastPosition, IssPosition oneBeforeLastPosition) {
        return lastPosition.getUnixTime() - oneBeforeLastPosition.getUnixTime() <= 1800;
    }

    private void openConnection() throws IOException, SQLException {
        Properties properties = new Properties();
        properties.load(Dao.class.getClassLoader().getResourceAsStream("application.properties"));
        connection = DriverManager.getConnection(properties.getProperty("url"), properties);
    }

    private void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

}
