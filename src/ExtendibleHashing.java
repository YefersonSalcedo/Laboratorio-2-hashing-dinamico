import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * IDEA GENERAL DEL ALGORITMO
 * ══════════════════════════════════════════════════════════════════════════════
 * El hashing extensible mantiene un DIRECTORIO de punteros y un conjunto de
 * BUCKETS (páginas de disco). Cada bucket almacena hasta BUCKET_SIZE registros.
 *
 * La clave (cédula) se hashea extrayendo sus bits menos significativos.
 * El número de bits que se usan es la "profundidad global" del directorio.
 * Cada bucket tiene su propia "profundidad local", que indica cuántos bits
 * de la clave son suficientes para identificar unívocamente ese bucket.
 *
 * Cuando un bucket se llena:
 *   1. Si su profundidad local < profundidad global -> se divide solo ese bucket.
 *   2. Si su profundidad local == profundidad global -> primero se duplica el
 *      directorio (aumenta la profundidad global en 1) y luego se divide.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * ESTRUCTURA EN DISCO  (3 archivos binarios)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  users.dat -> Almacena los registros completos de cada usuario.
 *    Formato por registro: [ cc : long (8 bytes) ][ nombre : UTF ][ correo : UTF ]
 *    el tamaño es variable por UTF
 *    Los registros se escriben al final del archivo.
 *    El "recordOffset" guardado en el índice apunta al byte donde inicia cada registro.
 *---------------------------------------------------------------------------------------------------------
 *  directory.dat -> Directorio de punteros al área de buckets.
 *
 *    Byte 0..7 : profundidad global (long).
 *                Indica cuántos bits menos significativos de la CC se usan
 *                actualmente para indexar. Con depth=2 se usan 2 bits -> 4
 *                combinaciones posibles (00, 01, 10, 11) -> 4 entradas en el directorio.
 *
 *    Byte 8..  : 2^globalDepth entradas consecutivas, 8 bytes (long) cada una.
 *                Cada entrada es el byte-offset del bucket correspondiente en buckets.dat.
 *                Para encontrar el bucket de una CC se calcula hash(cc, globalDepth),
 *                se obtiene un índice [0, 2^depth - 1], y se lee la entrada en: posición = 8 + índice × 8
 *
 *                Ejemplo con globalDepth=2 y CC=1234565 → hash → índice 01:
 *                  byte  0.. 7  ->  2          (globalDepth)
 *                  byte  8..15  ->  0          (dir[00] -> bucket en offset   0 de buckets.dat)
 *                  byte 16..23  ->  80         (dir[01] -> bucket en offset  80) <- esta CC va aquí
 *                  byte 24..31  ->  160        (dir[10] -> bucket en offset 160)
 *                  byte 32..39  ->  80         (dir[11] -> bucket en offset  80) <- alias
 *
 *    ALIAS: varias entradas pueden apuntar al MISMO bucket.
 *                Ocurre justo después de duplicarDirectorio(): el directorio dobla su
 *                número de entradas pero los buckets no cambian todavía. Cada bucket
 *                nuevo queda con dos entradas apuntándole (un alias).
 *
 *                Ejemplo — al pasar de depth=1 a depth=2:
 *                  Antes:   dir[0]->bktA   dir[1]->bktB
 * 
 *                  Después: dir[00]->bktA  dir[01]->bktB
 *                           dir[10]->bktA  dir[11]->bktB  ← alias de bktA y bktB
 *
 *                Los alias se resuelven cuando el bucket se llena y se divide:
 *                dividirBucket() actualiza UNO de los dos alias para que apunte
 *                al nuevo bucket hermano, y el otro sigue al bucket original.
 *---------------------------------------------------------------------------------------------------
 *  buckets.dat -> Área contigua de buckets (páginas del índice).
 *    Cada bucket ocupa exactamente BUCKET_BYTES bytes:
 *      [ localDepth : long (8 bytes) ] — profundidad local de este bucket
 *      [ count      : long (8 bytes) ] — número de slots ocupados actualmente
 *      [ slot_0_cc  : long (8 bytes) ] — cédula del registro 0
 *      [ slot_0_off : long (8 bytes) ] — offset de ese registro en users.dat
 *      [ slot_1_cc  : long (8 bytes) ] — ...repite hasta BUCKET_SIZE slots...
 *      [ slot_1_off : long (8 bytes) ]
 *      ...
 *    Los slots no usados se inicializan con NULL_OFFSET (-1).
 */
public class ExtendibleHashing {

    // =========================================================================
    // Constantes - rutas de archivos y parámetros del layout
    // =========================================================================

    static final String USERS_FILE     = "data/users.dat";
    static final String DIRECTORY_FILE = "data/directory.dat";
    static final String BUCKETS_FILE   = "data/buckets.dat";

    /**
     * Capacidad de cada bucket (número máximo de registros por página).
     * Cuando un bucket llega a este límite y se intenta insertar uno más,
     * se dispara la división (split) del bucket.
     */
    private static final int BUCKET_SIZE = 6;

    /**
     * Tamaño fijo en bytes de cada bucket en disco.
     * Cálculo:
     *   8 bytes (localDepth) + 8 bytes (count) + BUCKET_SIZE × (8 cc + 8 offset)
     */
    static final long BUCKET_BYTES = 8 + 8 + (long) BUCKET_SIZE * (8 + 8);

    /**
     * Profundidad global máxima permitida.
     * Con depth=20 el directorio tendría 2^20 = 1_048_576 entradas × 8 bytes = 8 MB.
     * Sirve como guardia para evitar crecimiento desbocado del directorio.
     */
    private static final int MAX_DEPTH = 20;

    /**
     * Centinela para slots vacíos y offsets no inicializados.
     * -1 es seguro porque los offsets reales siempre son ≥ 0.
     */
    private static final long NULL_OFFSET = -1L;


    // =========================================================================
    // Inicialización
    // =========================================================================

    // Elimina los tres archivos de datos y los vuelve a crear desde cero.
    public void resetFiles() throws IOException {
        Files.deleteIfExists(Path.of(USERS_FILE));
        Files.deleteIfExists(Path.of(DIRECTORY_FILE));
        Files.deleteIfExists(Path.of(BUCKETS_FILE));
        inicializar();
    }

    /**
     * Crea el estado inicial del índice: profundidad global = 1 y dos buckets vacíos.
     *
     * Estado inicial del directorio:
     *   globalDepth = 1
     *   dir[0] -> bucket0  (recibe CCs cuyo bit 0 = 0)
     *   dir[1] -> bucket1  (recibe CCs cuyo bit 0 = 1)
     *
     * Ambos buckets tienen localDepth = 1 y count = 0.
     * Si los archivos ya existen, sus contenidos se sobreescriben.
     */
    public void inicializar() throws IOException {

        // Crear los dos buckets raíz; crearBucket() los escribe al final de buckets.dat
        // y retorna el byte offset donde quedaron escritos.
        long bucket0 = crearBucket(1); // bucket para índice de directorio 0 
        long bucket1 = crearBucket(1); // bucket para índice de directorio 1

        // Escribir directory.dat: primero la profundidad global, luego los dos punteros.
        try (RandomAccessFile dir = new RandomAccessFile(DIRECTORY_FILE, "rw")) {
            dir.writeLong(1L);    // profundidad global inicial = 1
            dir.writeLong(bucket0); // dir[0] -> offset de bucket0 en buckets.dat
            dir.writeLong(bucket1); // dir[1] -> offset de bucket1 en buckets.dat
        }
    }

    // =========================================================================
    // Función hash
    // =========================================================================

    /**
     * Calcula el índice de directorio para una cédula dada la profundidad actual.
     *
     * Funcionamiento:
     *   Se construye una máscara de 'depth' bits en 1.
     *   Se aplica AND bit a bit con |cc| para extraer solo los 'depth' bits menos
     *   significativos. Ese entero es el índice en el directorio.
     *
     * Ejemplo:
     *   cc = 1245789  ->  binario:    100110000001001011101
     *   depth = 3     ->  mask   :    000000000000000000111
     *   resultado     :               ...       &       101 = 101 = 5
     *
     * Se usa Math.abs() para manejar cédulas negativas (aunque no deberían existir).
     */
    private int hash(long cc, int depth) {
        int mask = (1 << depth) - 1;
        return (int) (Math.abs(cc) & mask);
    }

    // =========================================================================
    // Operaciones principales
    // =========================================================================

    /**
     * Inserta un usuario en el sistema de dos pasos:
     *   1. Escribe el registro completo al final de users.dat y obtiene su offset.
     *   2. Inserta (cc -> recordOffset) en el índice hash (directory + buckets).
     *
     * La separación entre datos e índice permite que la búsqueda secuencial
     * (SequentialSearch) recorra users.dat sin tocar el índice, y que la búsqueda
     * por hash solo lea los buckets relevantes sin leer registros completos innecesarios.
     *
     * @param u Usuario a insertar (debe tener cc, nombre y correo).
     * @return  byte offset donde quedó escrito el registro en users.dat.
     * @throws IllegalArgumentException si la CC ya existe en el índice.
     */
    public long insertar(Usuario u) throws IOException {

        // Verificar duplicado ANTES de escribir en disco (evita registros huérfanos).
        if (existeCC(u.getCc())) {
            throw new IllegalArgumentException("CC duplicada: " + u.getCc());
        }

        // Paso 1: escribir el registro en users.dat
        // Siempre se agrega al final (append). El offset queda como "dirección"
        // del registro; el índice lo usará para saltar directo a este byte al buscar.
        long recordOffset;
        try (RandomAccessFile users = new RandomAccessFile(USERS_FILE, "rw")) {
            recordOffset = users.length();    // posición del nuevo registro = fin del archivo
            users.seek(recordOffset);
            users.writeLong(u.getCc());       // 8 bytes fijos
            users.writeUTF(u.getNombre());    // 2 bytes de longitud + n bytes del string
            users.writeUTF(u.getCorreo());    // 2 bytes de longitud + n bytes del string
        }

        // Paso 2: registrar la entrada en el índice hash
        // Solo guardamos (cc, recordOffset) en el bucket
        // el nombre y correo permanecen únicamente en users.dat para no duplicar datos.
        insertarEnIndice(u.getCc(), recordOffset);

        return recordOffset;
    }

    /**
     * Busca un usuario por su cédula usando el índice hash.
     *
     * Proceso:
     *   1. Calcula el índice del directorio con hash(cc, globalDepth).
     *   2. Lee el offset del bucket correspondiente en directory.dat.
     *   3. Escanea solo los slots de ESE bucket (máximo BUCKET_SIZE comparaciones).
     *   4. Si encuentra la CC, salta directamente al recordOffset en users.dat
     *      para leer nombre y correo.
     *
     * La ventaja frente a la búsqueda secuencial es que nunca lee más de
     * BUCKET_SIZE entradas del índice, independientemente del total de usuarios
     * registrados, siempre que la función hash distribuya bien los registros.
     *
     * @param ccBuscada número de cédula a localizar
     * @return Usuario encontrado, o null si no existe.
     */
    public Usuario buscar(long ccBuscada) throws IOException {
        int  globalDepth  = leerProfundidadGlobal();
        int  idx          = hash(ccBuscada, globalDepth);
        long bucketOffset = leerEntradaDirectorio(idx);

        try (RandomAccessFile bkts  = new RandomAccessFile(BUCKETS_FILE, "r");
            RandomAccessFile users = new RandomAccessFile(USERS_FILE, "r")) {

            bkts.seek(bucketOffset);

            // tomamos el tamaño del bucket en bytes y leerlo de golpe:
            byte[] buf = new byte[(int) BUCKET_BYTES];
            bkts.readFully(buf); // UNA sola syscall — evita múltiples accesos al disco

            // Envolver el buffer en un DataInputStream para leer los campos
            // del bucket (localDepth, count, slots) sin más accesos al disco
            DataInputStream dis = new DataInputStream(
                new java.io.ByteArrayInputStream(buf)
            );
            dis.readLong();        // saltar localDepth (no se necesita en la búsqueda)
            long count = dis.readLong(); // número de slots ocupados en este bucket

            for (int i = 0; i < count; i++) {
                long cc           = dis.readLong(); // cédula almacenada en el slot i
                long recordOffset = dis.readLong(); // offset del registro en users.dat

                if (cc == ccBuscada) {
                    // Salto directo al registro en users.dat usando el offset del slot
                    users.seek(recordOffset);
                    long   foundCc     = users.readLong();
                    String foundNombre = users.readUTF();
                    String foundCorreo = users.readUTF();
                    return new Usuario(foundCc, foundNombre, foundCorreo);
                }
            }
        }
        return null; // la CC no existe en el índice
    }

    /**
     * Indica si una cédula ya existe en el índice.
     * Reutiliza buscar() para no duplicar lógica.
     * Captura FileNotFoundException porque los archivos pueden no existir aún
     * (primera inserción antes de inicializar()).
     *
     * @param cc cédula a verificar
     * @return true si ya existe, false en caso contrario.
     */
    public boolean existeCC(long cc) throws IOException {
        try {
            return buscar(cc) != null;
        } catch (java.io.FileNotFoundException e) {
            // Los archivos de índice no existen todavía por lo tanto la CC no puede existir.
            return false;
        }
    }

    // =========================================================================
    // Lógica interna: inserción en el índice y división de buckets
    // =========================================================================
    
    /**
     * Punto de entrada interno para indexar (cc -> recordOffset) en el árbol hash.
     * Decide si hay espacio en el bucket destino o si hay que dividirlo.
     *
     * @param cc           cédula del registro
     * @param recordOffset posición del registro en users.dat
     */
    private void insertarEnIndice(long cc, long recordOffset) throws IOException {

        int  globalDepth  = leerProfundidadGlobal();
        int  idx          = hash(cc, globalDepth);      // índice en el directorio
        long bucketOffset = leerEntradaDirectorio(idx); // bucket destino

        long count = leerCountBucket(bucketOffset); // cuántos slots tiene ocupados

        if (count < BUCKET_SIZE) {
            // El bucket tiene espacio: insertar directamente.
            agregarSlot(bucketOffset, cc, recordOffset);
        } else {
            // El bucket está lleno: hay que dividirlo antes de insertar.
            dividirBucket(bucketOffset, idx, cc, recordOffset);
        }
    }

    /**
     * Divide el bucket lleno en dos y redistribuye todos sus registros (más el nuevo).
     *
     * Visión general del proceso:
     *   Antes de dividir, el bucket tenía localDepth bits de discriminación.
     *   Después tendrá localDepth+1 bits, lo que crea dos grupos:
     *     - Registros cuyo bit (localDepth) = 0  ->  quedan en el bucket original.
     *     - Registros cuyo bit (localDepth) = 1  ->  van al nuevo bucket hermano.
     *
     * Los 6 pasos son:
     *   1. Si localDepth == globalDepth, duplicar el directorio primero.
     *   2. Crear el bucket hermano con la nueva profundidad local.
     *   3. Leer todos los slots del bucket original y vaciarlo.
     *   4. Armar un arreglo temporal con esos slots + el nuevo registro.
     *   5. Actualizar el directorio: entradas que tenían el splitBit=1 ahora
     *      apuntan al bucket hermano.
     *   6. Reinsertar todos los registros temporales en su bucket correcto
     *      (puede desencadenar divisiones recursivas si todos los bits son iguales).
     *
     * @param bucketOffset    offset del bucket lleno en buckets.dat
     * @param dirIdx          índice del directorio que apuntaba a este bucket
     * @param newCc           cédula del registro que detonó el overflow
     * @param newRecordOffset offset del nuevo registro en users.dat
     */
    private void dividirBucket(long bucketOffset, int dirIdx, long newCc, long newRecordOffset) throws IOException {

        int globalDepth = leerProfundidadGlobal();
        int localDepth  = (int) leerLocalDepth(bucketOffset);

        // Paso 1: duplicar el directorio si no hay bits disponibles
        // Condición: si el bucket ya usa TODOS los bits actuales (localDepth == globalDepth),
        // necesitamos un bit más de discriminación -> duplicar el directorio.
        if (localDepth == globalDepth) {
            if (globalDepth >= MAX_DEPTH) {
                throw new IllegalStateException(
                    "Se alcanzó la profundidad máxima permitida: " + MAX_DEPTH);
            }
            duplicarDirectorio(); // ahora globalDepth se incrementa en disco
            globalDepth++;        // actualizar variable local para los pasos siguientes
        }

        int newLocalDepth = localDepth + 1; // nueva profundidad de ambos buckets tras la división

        // Paso 2: crear el bucket hermano
        // El nuevo bucket se escribe al final de buckets.dat.
        long newBucketOffset = crearBucket(newLocalDepth);

        // El bucket original también sube su profundidad local.
        escribirLocalDepth(bucketOffset, newLocalDepth);

        // Paso 3: leer y vaciar el bucket original 
        // Leemos todos los slots actuales en memoria y luego ponemos count=0.
        // NO borramos los bytes físicos, solo reiniciamos el contador;
        // agregarSlot() los sobreescribirá al reinsertar.
        long[][] slots = leerSlots(bucketOffset); // array [count][2] con {cc, recordOffset}
        vaciarBucket(bucketOffset);               // count = 0 (slots físicos intactos)

        // Paso 4: armar arreglo temporal con todos los registros a redistribuir
        // Incluye los 'count' registros existentes MÁS el nuevo registro que causó el split.
        long[] allCcs     = new long[BUCKET_SIZE + 1];
        long[] allOffsets = new long[BUCKET_SIZE + 1];
        int total = 0;
        for (long[] slot : slots) {
            allCcs[total]     = slot[0]; // cc del registro existente
            allOffsets[total] = slot[1]; // su offset en users.dat
            total++;
        }
        allCcs[total]     = newCc;           // el registro nuevo que detonó el split
        allOffsets[total] = newRecordOffset;
        total++;

        // Paso 5: redirigir punteros del directorio 
        // El "splitBit" es el bit en la posición (newLocalDepth - 1), el bit recién
        // ganado. Las entradas del directorio cuyo índice tenga ese bit en 1 deben
        // apuntar ahora al nuevo bucket hermano; las demás siguen al bucket original.
        //
        // Ejemplo: newLocalDepth=2, splitBit = 1<<1 = 0b10
        //   Entradas 0b00 y 0b01 → bucket original (splitBit & i == 0)
        //   Entradas 0b10 y 0b11 → nuevo bucket    (splitBit & i != 0)
        int size     = 1 << globalDepth; // total de entradas en el directorio ahora
        int splitBit = 1 << (newLocalDepth - 1); // bit que discrimina los dos nuevos buckets

        try (RandomAccessFile dir = new RandomAccessFile(DIRECTORY_FILE, "rw")) {
            dir.seek(8); // saltar los primeros 8 bytes (globalDepth)
            for (int i = 0; i < size; i++) {
                long ptr = dir.readLong(); // leer puntero de la entrada i
                if (ptr == bucketOffset) {
                    // Esta entrada apuntaba al bucket que acabamos de dividir.
                    if ((i & splitBit) != 0) {
                        // El índice i tiene el splitBit en 1 -> redirigir al hermano.
                        dir.seek(dir.getFilePointer() - 8); // retroceder al inicio de esta entrada
                        dir.writeLong(newBucketOffset);
                        // Si el splitBit es 0 en i, la entrada ya apunta al bucket original: no tocar.
                    }
                }
            }
        }

        // Paso 6: reinsertar todos los registros en su bucket correcto
        // Cada CC se vuelve a hashear con la nueva globalDepth para saber a cuál
        // de los dos buckets (original o hermano) pertenece ahora.
        for (int i = 0; i < total; i++) {
            int  newIdx    = hash(allCcs[i], globalDepth);
            long targetBkt = leerEntradaDirectorio(newIdx);
            long cnt       = leerCountBucket(targetBkt);

            if (cnt >= BUCKET_SIZE) {
                // Caso borde: tras dividir, el bucket destino también está lleno.
                // Ocurre cuando todos los registros tienen los mismos bits de hash
                // (colisión estructural). Se resuelve dividiendo recursivamente.
                //
                // TRUCO: agregarSlot() primero para que dividirBucket() pueda leer
                // el slot como parte del bucket lleno, y luego vaciarUltimoSlot()
                // deshace esa inserción temporal para no duplicar el registro
                // (dividirBucket lo reinsertará internamente como newCc).
                agregarSlot(targetBkt, allCcs[i], allOffsets[i]); // inserción temporal
                dividirBucket(targetBkt, newIdx, allCcs[i], allOffsets[i]); // split recursivo
                vaciarUltimoSlot(targetBkt); // revertir la inserción temporal
            } else {
                agregarSlot(targetBkt, allCcs[i], allOffsets[i]); // inserción normal
            }
        }
    }

    /**
     * Duplica el directorio aumentando la profundidad global en 1.
     *
     * Antes: globalDepth=d, directorio tiene 2^d entradas.
     * Después: globalDepth=d+1, directorio tiene 2^(d+1) = 2×2^d entradas.
     *
     * La regla de duplicación:
     *   La nueva entrada i del directorio ampliado apunta al mismo bucket
     *   que la entrada (i % 2^d) del directorio anterior.
     *   Es decir, dir_nuevo[i] = dir_viejo[i mod oldSize].
     *
     * Ejemplo con d=1 (2 entradas → 4 entradas):
     *   dir_viejo: [bkt_A, bkt_B]
     *   dir_nuevo:
     *     i=0 → ptrs[0 % 2] = ptrs[0] = bkt_A
     *     i=1 → ptrs[1 % 2] = ptrs[1] = bkt_B
     *     i=2 → ptrs[2 % 2] = ptrs[0] = bkt_A   ← nuevo alias de bkt_A
     *     i=3 → ptrs[3 % 2] = ptrs[1] = bkt_B   ← nuevo alias de bkt_B
     *
     * Los buckets no se modifican; solo el directorio crece.
     * Los "alias" son entradas que apuntan al mismo bucket hasta que ese
     * bucket se divida y el alias se actualice.
     */
    private void duplicarDirectorio() throws IOException {

        int    oldDepth = leerProfundidadGlobal();
        int    oldSize  = 1 << oldDepth;  // número de entradas actuales
        int    newSize  = oldSize * 2;    // número de entradas tras duplicar
        int    newDepth = oldDepth + 1;

        // Leer todos los punteros actuales a memoria antes de reescribir el archivo.
        long[] ptrs = new long[oldSize];
        try (RandomAccessFile dir = new RandomAccessFile(DIRECTORY_FILE, "r")) {
            dir.seek(8); // saltar los 8 bytes de globalDepth
            for (int i = 0; i < oldSize; i++) {
                ptrs[i] = dir.readLong();
            }
        }

        // Reescribir directory.dat desde el byte 0 con la nueva profundidad y los nuevos punteros.
        try (RandomAccessFile dir = new RandomAccessFile(DIRECTORY_FILE, "rw")) {
            dir.seek(0);
            dir.writeLong(newDepth); // nueva profundidad global
            for (int i = 0; i < newSize; i++) {
                // ptrs[i % oldSize] aplica la regla de duplicación:
                // cada entrada nueva es un alias de la entrada anterior correspondiente.
                dir.writeLong(ptrs[i % oldSize]);
            }
        }
    }

    // =========================================================================
    // Funciones auxiliares - operaciones sobre buckets.dat
    // =========================================================================

    
    /**
     * Crea un nuevo bucket vacío al final de buckets.dat y retorna su offset.
     *
     * Layout del bucket recién creado:
     *   [localDepth: long] [count=0: long] [slot_0_cc=-1: long] [slot_0_off=-1: long] ...
     *
     * Todos los slots se inicializan con NULL_OFFSET (-1) como centinela de "vacío".
     * El offset retornado es la dirección que el directorio almacenará.
     *
     * @param localDepth profundidad local inicial de este bucket
     * @return byte offset del inicio del bucket en buckets.dat
     */
    private long crearBucket(int localDepth) throws IOException {
        try (RandomAccessFile bkts = new RandomAccessFile(BUCKETS_FILE, "rw")) {
            long offset = bkts.length(); // el nuevo bucket empieza al final del archivo
            bkts.seek(offset);
            bkts.writeLong(localDepth); // profundidad local
            bkts.writeLong(0L);         // count = 0 (ningún slot ocupado)
            // Escribir BUCKET_SIZE slots vacíos (-1 en cc y en recordOffset)
            for (int i = 0; i < BUCKET_SIZE; i++) {
                bkts.writeLong(NULL_OFFSET); // cc del slot i = vacío
                bkts.writeLong(NULL_OFFSET); // recordOffset del slot i = vacío
            }
            return offset; // devolver la dirección del bucket recién creado
        }
    }

    /**
     * Lee la profundidad local de un bucket.
     * La profundidad local está en los primeros 8 bytes del bucket.
     *
     * @param bucketOffset offset del bucket en buckets.dat
     * @return profundidad local actual
     */
    private long leerLocalDepth(long bucketOffset) throws IOException {
        try (RandomAccessFile bkts = new RandomAccessFile(BUCKETS_FILE, "r")) {
            bkts.seek(bucketOffset); // el primer campo del bucket es localDepth
            return bkts.readLong();
        }
    }

    /**
     * Actualiza la profundidad local de un bucket en disco.
     * Se usa durante una división para reflejar que el bucket ahora discrimina
     * un bit más de la cédula.
     *
     * @param bucketOffset offset del bucket en buckets.dat
     * @param depth        nueva profundidad local a escribir
     */
    private void escribirLocalDepth(long bucketOffset, long depth) throws IOException {
        try (RandomAccessFile bkts = new RandomAccessFile(BUCKETS_FILE, "rw")) {
            bkts.seek(bucketOffset); // posición del campo localDepth
            bkts.writeLong(depth);
        }
    }

    /**
     * Lee el número de slots ocupados (count) de un bucket.
     * El campo count está en los bytes 8..15 del bucket (justo después de localDepth).
     *
     * @param bucketOffset offset del bucket en buckets.dat
     * @return número de registros actualmente almacenados en el bucket
     */
    private long leerCountBucket(long bucketOffset) throws IOException {
        try (RandomAccessFile bkts = new RandomAccessFile(BUCKETS_FILE, "r")) {
            bkts.seek(bucketOffset + 8); // +8 para saltar los bytes de localDepth
            return bkts.readLong();
        }
    }

    /**
     * Agrega un slot (cc, recordOffset) al siguiente espacio libre del bucket
     * e incrementa su count en 1.
     *
     * Layout de memoria del bucket para calcular la posición del slot libre:
     *   offset base
     *     + 8  (localDepth)
     *     + 8  (count)
     *     + count × 16  (16 bytes por slot: 8 cc + 8 recordOffset)
     *
     * Precondición: count < BUCKET_SIZE (el llamador debe verificarlo).
     *
     * @param bucketOffset offset del bucket en buckets.dat
     * @param cc           cédula a guardar en el slot
     * @param recordOffset offset del registro completo en users.dat
     */
    private void agregarSlot(long bucketOffset, long cc, long recordOffset) throws IOException {
        try (RandomAccessFile bkts = new RandomAccessFile(BUCKETS_FILE, "rw")) {
            bkts.seek(bucketOffset + 8); // posición del campo count
            long count = bkts.readLong();

            // Calcular el byte exacto donde empieza el próximo slot libre.
            // Cada slot ocupa 16 bytes (8 cc + 8 recordOffset).
            long slotPos = bucketOffset + 8 + 8 + count * (8 + 8);
            bkts.seek(slotPos);
            bkts.writeLong(cc);           // escribir cédula
            bkts.writeLong(recordOffset); // escribir offset del registro en users.dat

            // Actualizar count: volver al campo count y escribir count+1.
            bkts.seek(bucketOffset + 8);
            bkts.writeLong(count + 1);
        }
    }

    /**
     * Lee todos los slots ocupados de un bucket y los retorna en memoria.
     * Se usa durante una división para rescatar los registros antes de vaciar el bucket.
     *
     * @param bucketOffset offset del bucket en buckets.dat
     * @return array [count][2] donde [i][0] = cc y [i][1] = recordOffset del slot i
     */
    private long[][] leerSlots(long bucketOffset) throws IOException {
        try (RandomAccessFile bkts = new RandomAccessFile(BUCKETS_FILE, "r")) {
            bkts.seek(bucketOffset + 8); // saltar localDepth, leer desde count
            long count = bkts.readLong();
            long[][] slots = new long[(int) count][2];
            for (int i = 0; i < count; i++) {
                slots[i][0] = bkts.readLong(); // cc del slot i
                slots[i][1] = bkts.readLong(); // recordOffset del slot i
            }
            return slots;
        }
    }

    /**
     * Vacía lógicamente un bucket poniendo su count en 0.
     * NO borra los bytes de los slots; simplemente el contador queda en 0
     * y agregarSlot() los sobreescribirá en inserciones futuras.
     * Es más rápido que limpiar cada slot físicamente.
     *
     * @param bucketOffset offset del bucket en buckets.dat
     */
    private void vaciarBucket(long bucketOffset) throws IOException {
        try (RandomAccessFile bkts = new RandomAccessFile(BUCKETS_FILE, "rw")) {
            bkts.seek(bucketOffset + 8); // posición del campo count
            bkts.writeLong(0L);          // count = 0
        }
    }

    /**
     * Deshace la última inserción de un slot decrementando count en 1.
     *
     * Se usa exclusivamente en el Paso 6 de dividirBucket() para revertir
     * la inserción temporal que se hace antes de la división recursiva.
     * Sin este método, el registro quedaría duplicado (insertado manualmente
     * Y luego reinsertado por la recursión).
     *
     * No borra los bytes del slot; solo ajusta el contador.
     *
     * @param bucketOffset offset del bucket en buckets.dat
     */
    private void vaciarUltimoSlot(long bucketOffset) throws IOException {
        try (RandomAccessFile bkts = new RandomAccessFile(BUCKETS_FILE, "rw")) {
            bkts.seek(bucketOffset + 8); // posición del campo count
            long count = bkts.readLong();
            if (count > 0) {
                bkts.seek(bucketOffset + 8);
                bkts.writeLong(count - 1); // decrementar count
            }
        }
    }

    // =========================================================================
    // Funciones auxiliares - operaciones sobre directory.dat
    // =========================================================================

    /**
     * Lee la profundidad global desde el inicio de directory.dat.
     * Este valor determina cuántos bits de la CC se usan en hash() y
     * cuántas entradas tiene el directorio (2^globalDepth).
     *
     * @return profundidad global actual
     */
    int leerProfundidadGlobal() throws IOException {
        try (RandomAccessFile dir = new RandomAccessFile(DIRECTORY_FILE, "r")) {
            dir.seek(0); // la profundidad global siempre está en el byte 0
            return (int) dir.readLong();
        }
    }

    /**
     * Lee el offset del bucket al que apunta la entrada idx del directorio.
     *
     * Cálculo del offset en disco:
     *   8 bytes (globalDepth) + idx × 8 bytes (cada entrada es un long)
     *
     * @param idx índice en el directorio [0, 2^globalDepth - 1]
     * @return offset del bucket correspondiente en buckets.dat
     */
    long leerEntradaDirectorio(int idx) throws IOException {
        try (RandomAccessFile dir = new RandomAccessFile(DIRECTORY_FILE, "r")) {
            dir.seek(8 + (long) idx * 8); // saltar globalDepth y las idx entradas anteriores
            return dir.readLong();
        }
    }

    // =========================================================================
    // Diagnóstico - impresión del estado completo del índice
    // =========================================================================

    /**
     * Imprime en consola el estado actual de todo el índice hash.
     * Usarlo para depuración y para verificar que la estructura en disco es coherente.
     *
     * Para cada entrada del directorio muestra:
     *   - Su representación binaria (los bits que usa para discriminar)
     *   - El offset del bucket al que apunta
     *   - localDepth y count del bucket
     *   - Todos los slots (CC + recordOffset)
     *
     * Los buckets compartidos (varios alias en el directorio) solo se imprimen
     * una vez; las entradas duplicadas muestran "(ya impreso)".
     */
    public void printEstado() throws IOException {

        int globalDepth = leerProfundidadGlobal();
        int dirSize     = 1 << globalDepth; // número total de entradas en el directorio

        System.out.println("==================================================");
        System.out.println("ESTADO DEL EXTENDIBLE HASHING");
        System.out.printf ("  Profundidad global: %d  |  Entradas directorio: %d%n",
                           globalDepth, dirSize);
        System.out.println("==================================================");

        // Rastrear qué buckets ya se imprimieron para no repetirlos
        // (varios índices del directorio pueden apuntar al mismo bucket).
        java.util.Set<Long> visitados = new java.util.LinkedHashSet<>();

        try (RandomAccessFile dir  = new RandomAccessFile(DIRECTORY_FILE, "r");
             RandomAccessFile bkts = new RandomAccessFile(BUCKETS_FILE, "r")) {

            dir.seek(8); // saltar globalDepth, posicionarse en la primera entrada

            for (int i = 0; i < dirSize; i++) {
                long bktOffset = dir.readLong();

                // Mostrar el índice i en binario con relleno de ceros (ancho = globalDepth)
                String bits = String.format("%" + globalDepth + "s",
                              Integer.toBinaryString(i)).replace(' ', '0');
                System.out.printf("  dir[%s] -> bucket@%d", bits, bktOffset);

                if (visitados.contains(bktOffset)) {
                    System.out.println("  (ya impreso)"); // alias: no imprimir el bucket de nuevo
                    continue;
                }
                visitados.add(bktOffset);

                // Leer encabezado del bucket
                bkts.seek(bktOffset);
                long localDepth = bkts.readLong();
                long count      = bkts.readLong();

                System.out.printf("  [localDepth=%d, count=%d]%n", localDepth, count);

                // Imprimir cada slot ocupado del bucket
                for (int j = 0; j < count; j++) {
                    long cc           = bkts.readLong();
                    long recordOffset = bkts.readLong();
                    System.out.printf("      Slot %d: CC=%-10d  recordOffset=%d%n",
                                      j, cc, recordOffset);
                }

                // Posicionar el cursor del archivo al final del bucket
                // para que la próxima lectura de bkts sea coherente.
                bkts.seek(bktOffset + BUCKET_BYTES);
            }
        }
        System.out.println("==================================================");
    }
}