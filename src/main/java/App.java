import dao.Dao;
import dao.IDao;

public class App {
    public static void main(String[] args) {

        IDao dao = new Dao();
        dao.processingStart();

    }

}
