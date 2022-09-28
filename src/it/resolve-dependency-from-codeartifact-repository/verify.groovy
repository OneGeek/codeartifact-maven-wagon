def checkFile(File file) {

    if (!file.isFile()) {

        throw new FileNotFoundException("file: " + file);
    }
}

checkFile(new File(basedir, "build.log"));
