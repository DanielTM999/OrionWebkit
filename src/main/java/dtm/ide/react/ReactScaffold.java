package dtm.ide.react;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

public final class ReactScaffold {
    public static Path component(Path directory, String name, boolean typescript) throws IOException {
        if (!name.matches("[A-Z][a-zA-Z0-9]*")) throw new IllegalArgumentException("Use um nome como MeuComponente.");
        Path file = directory.resolve(name + (typescript ? ".tsx" : ".jsx"));
        Files.writeString(file, "export default function " + name + "() {\n    return (\n        <div>" + name + "</div>\n    );\n}\n", StandardOpenOption.CREATE_NEW);
        return file;
    }

    public static Path project(Path parent, String name, boolean typescript) throws IOException {
        if (!name.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("Use um nome como minha-aplicacao.");
        Path root = parent.toAbsolutePath().normalize().resolve(name);
        Files.createDirectory(root);
        Files.createDirectory(root.resolve("src"));
        String extension = typescript ? "tsx" : "jsx";
        String typeDependencies = typescript ? ",\n    \"typescript\": \"^5.9.0\",\n    \"@types/react\": \"^19.0.0\",\n    \"@types/react-dom\": \"^19.0.0\"" : "";
        Map<String, String> files = Map.of(
                "package.json", """
                        {
                          "name": "%s",
                          "private": true,
                          "version": "0.0.0",
                          "type": "module",
                          "scripts": { "dev": "vite", "build": "%svite build", "preview": "vite preview" },
                          "dependencies": { "react": "^19.0.0", "react-dom": "^19.0.0" },
                          "devDependencies": { "vite": "^7.0.0", "@vitejs/plugin-react": "^5.0.0"%s }
                        }
                        """.formatted(name, typescript ? "tsc --noEmit && " : "", typeDependencies),
                "vite.config.js", "import { defineConfig } from 'vite';\nimport react from '@vitejs/plugin-react';\n\nexport default defineConfig({ plugins: [react()] });\n",
                "index.html", "<!doctype html>\n<html lang=\"pt-BR\">\n<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><title>React</title></head>\n<body><div id=\"root\"></div><script type=\"module\" src=\"/src/main." + extension + "\"></script></body>\n</html>\n",
                "src/main." + extension, "import { StrictMode } from 'react';\nimport { createRoot } from 'react-dom/client';\nimport App from './App';\nimport './style.css';\n\ncreateRoot(document.getElementById('root')" + (typescript ? "!" : "") + ").render(<StrictMode><App /></StrictMode>);\n",
                "src/style.css", "body { margin: 0; font-family: system-ui, sans-serif; }\n",
                ".gitignore", "node_modules/\ndist/\n.orion/\n");
        for (var entry : files.entrySet()) Files.writeString(root.resolve(entry.getKey()), entry.getValue(), StandardOpenOption.CREATE_NEW);
        component(root.resolve("src"), "App", typescript);
        if (typescript) Files.writeString(root.resolve("tsconfig.json"), """
                {
                  "compilerOptions": {
                    "target": "ES2022",
                    "lib": ["ES2022", "DOM", "DOM.Iterable"],
                    "module": "ESNext",
                    "moduleResolution": "bundler",
                    "jsx": "react-jsx",
                    "strict": true,
                    "skipLibCheck": true,
                    "noEmit": true
                  },
                  "include": ["src"]
                }
                """, StandardOpenOption.CREATE_NEW);
        return root;
    }
}
