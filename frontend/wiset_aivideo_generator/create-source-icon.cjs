const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

async function createSourceIcon() {
  // 创建 1024x1024 的源图标（Tauri 推荐使用大尺寸）
  const svg = `<svg width="1024" height="1024" xmlns="http://www.w3.org/2000/svg">
    <rect width="1024" height="1024" fill="#4ECDC4"/>
    <text x="512" y="680" font-family="Arial, sans-serif" font-size="560" font-weight="bold" text-anchor="middle" fill="white">W</text>
  </svg>`;

  const outputPath = path.join(__dirname, 'app-icon.png');

  await sharp(Buffer.from(svg))
    .resize(1024, 1024)
    .png()
    .toFile(outputPath);

  console.log('✓ Source icon created: app-icon.png');
  console.log('Now run: pnpm tauri icon app-icon.png');
}

createSourceIcon().catch(err => {
  console.error('Error:', err.message);
  process.exit(1);
});
