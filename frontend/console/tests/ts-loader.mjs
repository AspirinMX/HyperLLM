import { registerHooks } from "node:module";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const SRC_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../src",
);

function resolveWithExtension(candidatePath) {
  if (existsSync(candidatePath) && !candidatePath.endsWith(path.sep)) {
    return pathToFileURL(candidatePath).href;
  }
  for (const ext of [".ts", ".tsx", ".mts", ".js", ".mjs"]) {
    const withExt = `${candidatePath}${ext}`;
    if (existsSync(withExt)) {
      return pathToFileURL(withExt).href;
    }
  }
  const indexTs = path.join(candidatePath, "index.ts");
  if (existsSync(indexTs)) {
    return pathToFileURL(indexTs).href;
  }
  return null;
}

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith("@/")) {
      const target = path.join(SRC_ROOT, specifier.slice(2));
      const resolvedUrl = resolveWithExtension(target);
      if (resolvedUrl) {
        return { url: resolvedUrl, shortCircuit: true };
      }
    }

    if (
      (specifier.startsWith("./") || specifier.startsWith("../")) &&
      context.parentURL?.startsWith("file:")
    ) {
      const parentDir = path.dirname(fileURLToPath(context.parentURL));
      const target = path.resolve(parentDir, specifier);
      const resolvedUrl = resolveWithExtension(target);
      if (resolvedUrl) {
        return { url: resolvedUrl, shortCircuit: true };
      }
    }

    return nextResolve(specifier, context);
  },
});
