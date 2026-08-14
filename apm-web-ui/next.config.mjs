/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  reactStrictMode: true,
  async rewrites() {
    return [
      {
        source: '/api/v1/:path*',
        destination: process.env.COLLECTOR_URL ? `${process.env.COLLECTOR_URL}/api/v1/:path*` : 'http://localhost:8080/api/v1/:path*',
      },
    ];
  },
};

export default nextConfig;
