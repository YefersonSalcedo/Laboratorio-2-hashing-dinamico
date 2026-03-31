import java.io.IOException;
import java.util.Scanner;

public class Main {

    private static final Scanner sc = new Scanner(System.in);
    private static final ExtendibleHashing hashing = new ExtendibleHashing();
    private static final SequentialSearch sequential = new SequentialSearch();

    public static void main(String[] args) {
        try {
            if (!filesReady()) {
                // Si existen pero estan incompletos/corruptos, recreamos desde cero.
                hashing.resetFiles();
            }
            menu();
        } catch (IOException e) {
            System.err.println("Error de E/S: " + e.getMessage());
        }
    }

    private static boolean filesReady() {
        java.io.File users = new java.io.File("data/users.dat");
        java.io.File dir = new java.io.File("data/directory.dat");
        java.io.File buckets = new java.io.File("data/buckets.dat");

        if (!users.exists() || !dir.exists() || !buckets.exists()) {
            return false;
        }

        try {
            if (dir.length() < 24L) {
                return false;
            }
            if (buckets.length() < 2L * ExtendibleHashing.BUCKET_BYTES) {
                return false;
            }

            int globalDepth = hashing.leerProfundidadGlobal();
            if (globalDepth < 1 || globalDepth > 20) {
                return false;
            }

            long expectedDirectoryBytes = 8L + ((long) 1 << globalDepth) * 8L;
            return dir.length() >= expectedDirectoryBytes;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void menu() throws IOException {
        while (true) {
            System.out.println("\n===== LABORATORIO HASHING DINÁMICO =====");
            System.out.println("1. Registrar usuario");
            System.out.println("2. Buscar usuario");
            System.out.println("3. Mostrar estado del hash");
            System.out.println("4. Reiniciar archivos");
            System.out.println("5. Salir");
            System.out.print("Opción: ");

            String opcion = sc.nextLine().trim();
            switch (opcion) {
                case "1" -> registrar();
                case "2" -> buscar();
                case "3" -> hashing.printEstado();
                case "4" -> {
                    hashing.resetFiles();
                    System.out.println("Archivos reiniciados correctamente.");
                }
                case "5" -> {
                    System.out.println("Saliendo...");
                    return;
                }
                default -> System.out.println("Opción inválida.");
            }
        }
    }

    private static void registrar() {
        try {
            System.out.print("Nombre: ");
            String nombre = sc.nextLine().trim();
            System.out.print("Cédula: ");
            long cc = leerLong();
            System.out.print("Correo: ");
            String correo = sc.nextLine().trim();

            if (nombre.isEmpty() || correo.isEmpty()) {
                System.out.println("Nombre y correo no pueden estar vacíos.");
                return;
            }

            Usuario u = new Usuario(cc, nombre, correo);
            hashing.insertar(u);
            System.out.println("Usuario registrado correctamente.");
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        } catch (IOException e) {
            System.out.println("Error al registrar: " + e.getMessage());
        }
    }

    private static void buscar() {
        try {
            System.out.print("Cédula a buscar: ");
            long cc = leerLong();

            long inicioHash = System.nanoTime();
            Usuario porHash = hashing.buscar(cc);
            long tiempoHashNs = System.nanoTime() - inicioHash;

            Usuario porSec = sequential.buscar(cc);
            double tiempoSecMs = sequential.getTiempoMs();

            if (porHash == null || porSec == null) {
                System.out.println("Usuario no encontrado.");
                System.out.printf("Tiempo búsqueda (Hashing): %.4f ms%n", tiempoHashNs / 1_000_000.0);
                System.out.printf("Tiempo búsqueda (Secuencial): %.4f ms%n", tiempoSecMs);
                return;
            }

            System.out.println("\nUsuario encontrado:");
            System.out.println("Nombre: " + porHash.getNombre());
            System.out.println("CC: " + porHash.getCc());
            System.out.println("Correo: " + porHash.getCorreo());
            System.out.printf("Tiempo búsqueda (Hashing): %.4f ms%n", tiempoHashNs / 1_000_000.0);
            System.out.printf("Tiempo búsqueda (Secuencial): %.4f ms%n", tiempoSecMs);
        } catch (IOException e) {
            System.out.println("Error al buscar: " + e.getMessage());
        }
    }

    private static long leerLong() {
        while (true) {
            String input = sc.nextLine().trim();
            try {
                return Long.parseLong(input);
            } catch (NumberFormatException e) {
                System.out.print("Ingrese un número válido: ");
            }
        }
    }
}