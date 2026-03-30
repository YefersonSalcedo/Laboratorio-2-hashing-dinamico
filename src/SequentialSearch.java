import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;

public class SequentialSearch {
    
    private static final String USERS_FILE = "data/users.dat";
    private long comparaciones;
    private long tiempoNs;

    public Usuario buscar(long ccBuscada) {
        this.comparaciones = 0;
        Usuario encontrado = null;
        
        long inicio = System.nanoTime();

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(USERS_FILE)))) {
            while (true) {
                long currentCc = dis.readLong();
                this.comparaciones++;
                
                if (currentCc == ccBuscada) {
                    String nombre = dis.readUTF();
                    String correo = dis.readUTF();
                    encontrado = new Usuario(currentCc, nombre, correo);
                    break;
                } else {
                    dis.readUTF();
                    dis.readUTF();
                }
            }
        } catch (EOFException e) {
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }

        this.tiempoNs = System.nanoTime() - inicio;
        
        return encontrado;
    }

    public long contarComparaciones() {
        return this.comparaciones;
    }

    public double getTiempoMs() {
        return this.tiempoNs / 1_000_000.0;
    }
}
