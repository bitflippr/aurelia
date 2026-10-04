// Bun's `file` loader: the import is the file's path.
declare module "*.ttf" {
  const path: string;
  export default path;
}
declare module "*.png" {
  const path: string;
  export default path;
}
