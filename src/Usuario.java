public class Usuario {
    private long cc;
    private String nombre;
    private String correo;

    public Usuario(long cc, String nombre, String correo) {
        this.cc = cc;
        this.nombre = nombre;
        this.correo = correo;
    }

    public long getCc() {
        return cc;
    }

    public String getNombre() {
        return nombre;
    }

    public String getCorreo() {
        return correo;
    }

    @Override
    public String toString() {
        return String.format("CC: %-12d | Nombre: %-20s | Correo: %s", cc, nombre, correo);
    }
}
